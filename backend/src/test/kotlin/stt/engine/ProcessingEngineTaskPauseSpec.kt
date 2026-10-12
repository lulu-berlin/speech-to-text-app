package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import stt.db.SpeakersMode
import stt.db.TaskPhase
import stt.queue.TaskStatus
import stt.transcribe.WhisperSegment
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ProcessingEngineTaskPauseSpec :
    BehaviorSpec({
        given("a task running when it is paused") {
            `when`("the task is paused and then resumed") {
                then("its process is frozen and continued, its status follows, and it still completes") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)

                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release
                        fixture.engine.start()

                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe task
                        statusOf(fixture, task) shouldBe TaskStatus.RUNNING

                        fixture.engine.pauseTask(task) shouldBe TaskCommandOutcome.Accepted
                        fixture.processControl.frozen shouldBe true
                        statusOf(fixture, task) shouldBe TaskStatus.PAUSED
                        phaseOf(fixture, task) shouldBe TaskPhase.TRANSCRIBING

                        // The resume goes through QUEUED at once and keeps the phase the task was paused in.
                        fixture.engine.resumeTask(task) shouldBe TaskCommandOutcome.Accepted
                        fixture.processControl.frozen shouldBe false
                        statusOf(fixture, task) shouldBe TaskStatus.RUNNING
                        phaseOf(fixture, task) shouldBe TaskPhase.TRANSCRIBING

                        fixture.transcriber.hold = null
                        release.complete(Unit)
                        fixture.completed.receive() shouldBe task
                        fixture.processControl.pauses shouldBe 1
                        fixture.processControl.resumes shouldBe 1
                    }
                }
            }
        }

        given("a task whose status does not allow the command") {
            `when`("pause or resume is sent for it") {
                then("it is refused with the reason and nothing changes") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)

                        // QUEUED allows only pause, so a resume is refused before the task has ever run.
                        fixture.engine.resumeTask(task) shouldBe
                            TaskCommandOutcome.Illegal(TaskStatus.QUEUED, TaskStatus.QUEUED)
                        statusOf(fixture, task) shouldBe TaskStatus.QUEUED

                        fixture.engine.start()
                        fixture.completed.receive() shouldBe task

                        // COMPLETED allows neither.
                        fixture.engine.pauseTask(task) shouldBe
                            TaskCommandOutcome.Illegal(TaskStatus.COMPLETED, TaskStatus.PAUSED)
                        fixture.engine.resumeTask(task) shouldBe
                            TaskCommandOutcome.Illegal(TaskStatus.COMPLETED, TaskStatus.QUEUED)
                        statusOf(fixture, task) shouldBe TaskStatus.COMPLETED

                        // An id no task has is reported as unknown, for either command.
                        fixture.engine.pauseTask(UNKNOWN_TASK) shouldBe TaskCommandOutcome.Unknown
                        fixture.engine.resumeTask(UNKNOWN_TASK) shouldBe TaskCommandOutcome.Unknown

                        // No refusal touched the running task's process.
                        fixture.processControl.pauses shouldBe 0
                        fixture.processControl.resumes shouldBe 0
                    }
                }
            }
        }
        given("a task being chunked when it is paused") {
            `when`("the task is paused and then resumed") {
                then("the chunking process is frozen and continued, and the task still completes") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)

                        val release = CompletableDeferred<Unit>()
                        fixture.chunker.hold = release
                        fixture.engine.start()

                        taskIdOf(fixture.chunker.entered.receive()) shouldBe task
                        statusOf(fixture, task) shouldBe TaskStatus.RUNNING
                        phaseOf(fixture, task) shouldBe TaskPhase.CHUNKING

                        fixture.engine.pauseTask(task) shouldBe TaskCommandOutcome.Accepted
                        fixture.processControl.frozen shouldBe true
                        statusOf(fixture, task) shouldBe TaskStatus.PAUSED

                        fixture.engine.resumeTask(task) shouldBe TaskCommandOutcome.Accepted
                        fixture.processControl.frozen shouldBe false
                        statusOf(fixture, task) shouldBe TaskStatus.RUNNING
                        phaseOf(fixture, task) shouldBe TaskPhase.CHUNKING

                        fixture.chunker.hold = null
                        release.complete(Unit)
                        fixture.completed.receive() shouldBe task
                        fixture.processControl.pauses shouldBe 1
                        fixture.processControl.resumes shouldBe 1
                    }
                }
            }
        }

        given("a queued task paused while another task runs") {
            `when`("the queued task is paused") {
                then("the running task is untouched and completes, and the paused task stays PAUSED, never started") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        // The running task has speakers, so its DIARIZING phase passes the pause gate.
                        val running =
                            enqueueTask(
                                fixture.tasks,
                                fixture.workDirectories,
                                "running.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val queued = enqueueTask(fixture.tasks, fixture.workDirectories, "queued.wav", queuePosition = 1, createdAt = 2)
                        fixture.transcriber.segmentsByTask =
                            mapOf(running to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")))

                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release
                        fixture.engine.start()
                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe running

                        fixture.engine.pauseTask(queued) shouldBe TaskCommandOutcome.Accepted
                        statusOf(fixture, queued) shouldBe TaskStatus.PAUSED
                        // The running task's process is not the paused task's: it is not frozen.
                        fixture.processControl.frozen shouldBe false
                        fixture.processControl.pauses shouldBe 0

                        fixture.transcriber.hold = null
                        release.complete(Unit)
                        pumpUntil(2.seconds) { statusOf(fixture, running) == TaskStatus.COMPLETED }
                        fixture.diarizer.passedGate shouldBe true
                        statusOf(fixture, running) shouldBe TaskStatus.COMPLETED

                        // The paused task is not started after the running one finishes.
                        pumpUntil(200.milliseconds) { fixture.chunker.workDirs.size > 1 }
                        fixture.chunker.workDirs.map(::taskIdOf) shouldBe listOf(running)
                        statusOf(fixture, queued) shouldBe TaskStatus.PAUSED
                    }
                }
            }
        }

        given("a running task paused in place and a queued task paused behind it") {
            `when`("the queued task is resumed") {
                then("the paused running task stays frozen, gated and PAUSED until it is resumed itself") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        // The running task has speakers, so its DIARIZING phase checks the pause gate.
                        val running =
                            enqueueTask(
                                fixture.tasks,
                                fixture.workDirectories,
                                "running.wav",
                                queuePosition = 0,
                                createdAt = 1,
                                speakersMode = SpeakersMode.AUTO,
                            )
                        val queued = enqueueTask(fixture.tasks, fixture.workDirectories, "queued.wav", queuePosition = 1, createdAt = 2)
                        fixture.transcriber.segmentsByTask =
                            mapOf(running to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")))

                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release
                        fixture.engine.start()
                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe running
                        fixture.engine.pauseTask(running) shouldBe TaskCommandOutcome.Accepted
                        fixture.engine.pauseTask(queued) shouldBe TaskCommandOutcome.Accepted

                        fixture.engine.resumeTask(queued) shouldBe TaskCommandOutcome.Accepted
                        statusOf(fixture, queued) shouldBe TaskStatus.QUEUED
                        // The resumed task is not the running one: the running task's process stays frozen.
                        fixture.processControl.frozen shouldBe true
                        fixture.processControl.resumes shouldBe 0
                        statusOf(fixture, running) shouldBe TaskStatus.PAUSED

                        // Its gate stays closed too: once its process ends (the fake ignores the freeze),
                        // its DIARIZING phase is held at the gate.
                        fixture.transcriber.hold = null
                        release.complete(Unit)
                        pumpUntil(200.milliseconds) { fixture.diarizer.passedGate }
                        fixture.diarizer.passedGate shouldBe false
                        statusOf(fixture, running) shouldBe TaskStatus.PAUSED

                        // Resuming the running task itself releases it; then the resumed queued task runs.
                        fixture.engine.resumeTask(running) shouldBe TaskCommandOutcome.Accepted
                        fixture.completed.receive() shouldBe running
                        fixture.diarizer.passedGate shouldBe true
                        fixture.completed.receive() shouldBe queued
                    }
                }
            }
        }

        given("a two-chunk task paused while its first chunk's process is already exiting") {
            `when`("the freeze misses that process") {
                then("no further process starts: it stops before its next chunk, PAUSED, until resumed, then completes") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, fixture.workDirectories, "task.wav", queuePosition = 0, createdAt = 1)
                        fixture.chunker.chunkCount = 2

                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release
                        fixture.engine.start()
                        fixture.transcriber.entered
                            .receive()
                            .fileName
                            .toString() shouldBe "chunk-0.flac"
                        fixture.engine.pauseTask(task) shouldBe TaskCommandOutcome.Accepted

                        // The fake process ignores the freeze, as when the pause lands while chunk 0's process
                        // exits: the pause now falls between two processes.
                        fixture.transcriber.hold = null
                        release.complete(Unit)
                        pumpUntil(300.milliseconds) { fixture.transcriber.audioFiles.size > 1 }
                        fixture.transcriber.audioFiles.map { it.fileName.toString() } shouldBe listOf("chunk-0.flac")
                        statusOf(fixture, task) shouldBe TaskStatus.PAUSED

                        // Resumed, it continues with the next chunk and completes.
                        fixture.engine.resumeTask(task) shouldBe TaskCommandOutcome.Accepted
                        fixture.completed.receive() shouldBe task
                        fixture.transcriber.audioFiles.map { it.fileName.toString() } shouldBe listOf("chunk-0.flac", "chunk-1.flac")
                    }
                }
            }
        }
    })

/** An id no task has: ids are autoincrement and start at 1. */
private const val UNKNOWN_TASK = 999L
