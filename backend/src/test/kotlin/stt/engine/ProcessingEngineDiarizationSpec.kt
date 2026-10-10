package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withContext
import stt.db.ChunkRepository
import stt.db.DiarizationStage
import stt.db.SegmentRepository
import stt.db.SpeakersMode
import stt.db.TaskPhase
import stt.queue.TaskStatus
import stt.transcribe.WhisperSegment
import java.nio.file.Files
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
                                fixture.workDirectories,
                                "speech.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val silence =
                            enqueueTask(
                                fixture.tasks,
                                fixture.workDirectories,
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
                                fixture.workDirectories,
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
                                fixture.workDirectories,
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
                                fixture.workDirectories,
                                "task.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val off =
                            enqueueTask(
                                fixture.tasks,
                                fixture.workDirectories,
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

        given("two tasks with the same stored chunks, one chunked by this run and one before it") {
            `when`("the engine diarizes both") {
                then("each gets a whole-file PCM rebuilt from its stored chunk FLACs, identical across the restart") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val restarted =
                            enqueueTask(
                                fixture.tasks,
                                fixture.workDirectories,
                                "restarted.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val fresh =
                            enqueueTask(
                                fixture.tasks,
                                fixture.workDirectories,
                                "fresh.wav",
                                queuePosition = 1,
                                createdAt = 2,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        // The "restart" task was chunked by a previous engine: its plan is stored and its
                        // original is gone. The other is chunked by this run.
                        ChunkRepository(db).storePlan(restarted, exportedPlan(fixture, restarted, chunkCount = 2))
                        withContext(Dispatchers.IO) { Files.delete(fixture.workDirectories.source(restarted)) }
                        fixture.chunker.chunkCount = 2
                        fixture.transcriber.segmentsByTask =
                            mapOf(
                                restarted to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")),
                                fresh to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")),
                            )

                        fixture.engine.start()
                        fixture.completed.receive() shouldBe restarted
                        fixture.completed.receive() shouldBe fresh

                        val expected = listOf(1.0.toFloat(), 2.0.toFloat(), 2.0.toFloat())
                        fixture.diarizer.diarized shouldBe listOf(restarted, fresh)
                        fixture.diarizer.pcms.map { it.toList() } shouldBe listOf(expected, expected)
                        fixture.pcmDecoder.decoded.map { it.fileName.toString() } shouldBe
                            listOf("chunk-0.flac", "chunk-1.flac", "chunk-0.flac", "chunk-1.flac")
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
                                fixture.workDirectories,
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
