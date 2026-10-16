package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import stt.db.ChunkRepository
import stt.db.DiarizationStage
import stt.db.SegmentRepository
import stt.db.SpeakersMode
import stt.db.TaskPhase
import stt.queue.TaskStatus
import stt.transcribe.WhisperSegment
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessingEngineDiarizationSpec :
    BehaviorSpec({
        given("two queued tasks, one with speech and one without") {
            `when`("the engine runs them") {
                then("the one with speech is diarized and the one without segments completes without diarization") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val speech =
                            enqueueTask(
                                fixture.tasks,
                                "speech.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val silence =
                            enqueueTask(
                                fixture.tasks,
                                "silence.wav",
                                queuePosition = 1,
                                createdAt = 2,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        fixture.transcriber.segmentsByTask =
                            mapOf(speech to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")))

                        fixture.engine.start()

                        fixture.completed.receive() shouldBe speech
                        fixture.completed.receive() shouldBe silence

                        fixture.diarizer.diarized shouldBe listOf(speech)
                    }
                }
            }
        }

        given("a task whose speakers are off, with speech") {
            `when`("the engine runs it") {
                then("it completes without diarization, its segments keep no speaker, and none is stored") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task =
                            enqueueTask(
                                fixture.tasks,
                                "task.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.OFF,
                            )
                        fixture.transcriber.segmentsByTask =
                            mapOf(task to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")))

                        fixture.engine.start()
                        fixture.completed.receive() shouldBe task

                        fixture.diarizer.diarized shouldBe emptyList()
                        SegmentRepository(db).findByTask(task).map { it.speakerId } shouldBe listOf<Int?>(null)
                        SegmentRepository(db).findSpeakers(task) shouldBe emptyList()
                    }
                }
            }
        }

        given("a task whose diarization is in progress") {
            `when`("the task is paused") {
                then("it stops at the pause gate and resumes from there") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task =
                            enqueueTask(
                                fixture.tasks,
                                "task.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        fixture.transcriber.segmentsByTask =
                            mapOf(task to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")))
                        val release = CompletableDeferred<Unit>()
                        fixture.diarizer.hold = release

                        fixture.engine.start()
                        fixture.diarizer.entered.receive() shouldBe task
                        phaseOf(fixture, task) shouldBe TaskPhase.DIARIZING

                        // Pause first: when the fake reaches the gate it must be stopped there.
                        fixture.engine.pauseTask(task) shouldBe TaskCommandOutcome.Accepted
                        statusOf(fixture, task) shouldBe TaskStatus.PAUSED
                        release.complete(Unit)

                        pumpUntil(200.milliseconds) { fixture.diarizer.passedGate }
                        fixture.diarizer.passedGate shouldBe false

                        fixture.engine.resumeTask(task) shouldBe TaskCommandOutcome.Accepted
                        pumpUntil(2.seconds) { fixture.diarizer.passedGate }
                        fixture.diarizer.passedGate shouldBe true
                        fixture.completed.receive() shouldBe task
                    }
                }
            }
        }

        given("a task being diarized and one whose speakers are off") {
            `when`("the diarizer reports its stages") {
                then(
                    "every report is persisted and published, the diarized task ends with no stage and progress 1, and the skipped task keeps null progress",
                ) {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task =
                            enqueueTask(
                                fixture.tasks,
                                "task.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val off =
                            enqueueTask(
                                fixture.tasks,
                                "off.wav",
                                queuePosition = 1,
                                createdAt = 2,
                                speakersMode = SpeakersMode.OFF,
                            )
                        fixture.transcriber.segmentsByTask =
                            mapOf(task to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")))

                        fixture.engine.start()
                        fixture.completed.receive() shouldBe task
                        fixture.completed.receive() shouldBe off

                        val reports = drain(fixture.progress)
                        reports.map { it.id }.distinct() shouldBe listOf(task)
                        reports.map { it.diarizationStage to it.diarizationProgress } shouldBe
                            listOf(
                                null to 0.0,
                                DiarizationStage.EMBEDDING to 0.0,
                                DiarizationStage.EMBEDDING to 0.5,
                                DiarizationStage.EMBEDDING to 1.0,
                                DiarizationStage.CLUSTERING to 0.0,
                                DiarizationStage.CLUSTERING to 1.0,
                                null to 1.0,
                            )

                        val finished =
                            fixture.tasks
                                .find(task)
                                ?.task
                                .shouldNotBeNull()
                        finished.diarizationStage shouldBe null
                        finished.diarizationProgress shouldBe 1.0
                        fixture.tasks
                            .find(off)
                            ?.task
                            ?.diarizationProgress shouldBe null
                    }
                }
            }
        }

        given("two tasks with the same stored clip, one chunked by this run and one before it") {
            `when`("the engine diarizes both") {
                then("each gets its stored clip as a window source, giving the same samples across the restart") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val restarted =
                            enqueueTask(
                                fixture.tasks,
                                "restarted.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val fresh =
                            enqueueTask(
                                fixture.tasks,
                                "fresh.wav",
                                queuePosition = 1,
                                createdAt = 2,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        // The "restart" task was chunked by a previous engine: its plan and its canonical clip
                        // are stored and its original is gone. The other is chunked by this run.
                        storeChunkedPlan(fixture, ChunkRepository(db), restarted, chunkCount = 2)
                        fixture.chunker.chunkCount = 2
                        fixture.transcriber.segmentsByTask =
                            mapOf(
                                restarted to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")),
                                fresh to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")),
                            )

                        fixture.engine.start()
                        fixture.completed.receive() shouldBe restarted
                        fixture.completed.receive() shouldBe fresh

                        // Diarization streams its speaker windows out of each task's stored canonical clip
                        // (item 43) — never the per-chunk copies the old reconstruction read (item 44). The
                        // window source of each run reads the same clip, so the same range gives the same
                        // samples, and the clip's own sample count comes from its stored header.
                        val expected = fixture.pcmDecoder.clipPcm.toList()
                        fixture.diarizer.diarized shouldBe listOf(restarted, fresh)
                        fixture.diarizer.sources.map { it.totalSamples } shouldBe listOf(16_000, 16_000)
                        fixture.diarizer.sources.map { it.samples(0, expected.size).toList() } shouldBe listOf(expected, expected)
                        fixture.pcmDecoder.decoded.map { it.fileName.toString() } shouldBe
                            listOf("diarize-window.flac", "diarize-window.flac")
                    }
                }
            }
        }

        given("a task whose diarization is in progress when the queue is paused") {
            `when`("the queue is paused and then resumed") {
                then("the diarization stops at the pause gate and resumes from there") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task =
                            enqueueTask(
                                fixture.tasks,
                                "task.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        fixture.transcriber.segmentsByTask =
                            mapOf(task to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")))
                        val release = CompletableDeferred<Unit>()
                        fixture.diarizer.hold = release

                        fixture.engine.start()
                        fixture.diarizer.entered.receive() shouldBe task
                        phaseOf(fixture, task) shouldBe TaskPhase.DIARIZING

                        // A queue pause is not a task pause: the task keeps RUNNING, but its diarization
                        // must stop at the engine's gate until the queue resumes.
                        fixture.engine.pauseQueue()
                        statusOf(fixture, task) shouldBe TaskStatus.RUNNING
                        release.complete(Unit)

                        pumpUntil(200.milliseconds) { fixture.diarizer.passedGate }
                        fixture.diarizer.passedGate shouldBe false

                        fixture.engine.resumeQueue()
                        pumpUntil(2.seconds) { fixture.diarizer.passedGate }
                        fixture.diarizer.passedGate shouldBe true
                        fixture.completed.receive() shouldBe task
                    }
                }
            }
        }
    })
