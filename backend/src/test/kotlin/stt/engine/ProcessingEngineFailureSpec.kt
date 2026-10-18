package stt.engine

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import org.jetbrains.exposed.v1.jdbc.update
import stt.db.SettingsTable
import stt.db.SpeakersMode
import stt.db.TaskPhase
import stt.queue.TaskStatus
import stt.transcribe.TranscriptionException
import stt.transcribe.WhisperSegment
import stt.withCapturedLogs

class ProcessingEngineFailureSpec :
    BehaviorSpec({
        given("two queued tasks of which the first one's chunk fails to transcribe") {
            `when`("the engine runs them") {
                then("the first is FAILED with the failure's message and the worker moves on to the second") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val tasks = fixture.tasks
                        val first = enqueueTask(tasks, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(tasks, "second.wav", queuePosition = 1, createdAt = 2)
                        val message = "whisper-cli failed on chunk-0.flac (exit 2):\nerror: input file not found"
                        fixture.transcriber.failNext = TranscriptionException(message)

                        fixture.engine.start()

                        fixture.completed.receive() shouldBe second
                        val failed = storedTaskOf(fixture, first)
                        failed.status shouldBe TaskStatus.FAILED
                        failed.error shouldBe message
                        failed.finishedAt.shouldNotBeNull()
                        failed.phase shouldBe TaskPhase.TRANSCRIBING
                    }
                }
            }
        }
        given("a queued task and a stored settings row whose device cannot be parsed") {
            `when`("the engine starts the task") {
                then("the task is FAILED with the read's message and the worker still runs the next queued task") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val first = enqueueTask(fixture.tasks, "first.wav", queuePosition = 0, createdAt = 1)
                        // Passes the schema's CHECK (device LIKE 'gpu:%') but not parseStoredDevice: a corrupted row.
                        db.writeTransaction { SettingsTable.update { it[SettingsTable.device] = "gpu:x" } }

                        fixture.engine.start()

                        fixture.failed.receive() shouldBe first
                        val failed = storedTaskOf(fixture, first)
                        failed.status shouldBe TaskStatus.FAILED
                        failed.error shouldBe "stored device must be auto, cpu, or gpu:<index>, got \"gpu:x\""
                        failed.finishedAt.shouldNotBeNull()

                        db.writeTransaction { SettingsTable.update { it[SettingsTable.device] = "auto" } }
                        val second = enqueueTask(fixture.tasks, "second.wav", queuePosition = 1, createdAt = 2)
                        fixture.engine.taskCreated(second)

                        fixture.completed.receive() shouldBe second
                    }
                }
            }
        }
        given("two queued tasks of which the first one's phase throws an Error, not an Exception") {
            `when`("the engine runs them") {
                then("the first is FAILED with the Error's message and the worker moves on to the second") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val first = enqueueTask(fixture.tasks, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(fixture.tasks, "second.wav", queuePosition = 1, createdAt = 2)
                        // What a native library that failed to load leaves behind (the speaker model's ONNX runtime).
                        val message = "Could not initialize class ai.onnxruntime.OrtEnvironment"
                        fixture.transcriber.failNext = NoClassDefFoundError(message)

                        fixture.engine.start()

                        fixture.completed.receive() shouldBe second
                        val failed = storedTaskOf(fixture, first)
                        failed.status shouldBe TaskStatus.FAILED
                        failed.error shouldBe message
                        failed.finishedAt.shouldNotBeNull()
                        failed.phase shouldBe TaskPhase.TRANSCRIBING
                    }
                }
            }
        }
        given("two queued tasks of which the first one fails and the write that records its failure throws") {
            `when`("the engine runs them") {
                then(
                    "the failure is logged with the task's id and both errors, the first stays RUNNING for recovery, and the worker runs the second",
                ) {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val first = enqueueTask(fixture.tasks, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(fixture.tasks, "second.wav", queuePosition = 1, createdAt = 2)
                        fixture.transcriber.failNext = TranscriptionException("boom")
                        // The database refuses to record the first task's failure, as a failing disk would.
                        db.writeTransaction {
                            exec(
                                "CREATE TRIGGER refuse_failed BEFORE UPDATE OF status ON tasks " +
                                    "WHEN NEW.status = 'FAILED' AND OLD.id = $first " +
                                    "BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END",
                            )
                        }

                        withCapturedLogs(ProcessingEngine::class.java.name, Level.ERROR) { events ->
                            fixture.engine.start()

                            fixture.completed.receive() shouldBe second
                            storedTaskOf(fixture, first).status shouldBe TaskStatus.RUNNING
                            events shouldHaveSize 1
                            val logged = events.single()
                            logged.mdcPropertyMap["taskId"] shouldBe first.toString()
                            val recording = logged.throwableProxy.shouldNotBeNull()
                            recording.message shouldContain "disk I/O error"
                            recording.suppressed.map { it.message } shouldBe listOf("boom")
                        }
                    }
                }
            }
        }
        given("a task whose whisper-cli is killed by SIGTERM while it transcribes its second chunk, and the app keeps running") {
            `when`("the engine runs it and the next queued task") {
                then("the task goes back to QUEUED, never FAILED, and the engine runs it again from its interrupted chunk") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        fixture.chunker.chunkCount = 2
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        val next = enqueueTask(fixture.tasks, "next.wav", queuePosition = 1, createdAt = 2)
                        val secondChunk = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = secondChunk
                        fixture.transcriber.holdFrom = 2

                        fixture.engine.start()
                        nextChunkEntered(fixture.transcriber) shouldBe "chunk-0.flac"
                        nextChunkEntered(fixture.transcriber) shouldBe "chunk-1.flac"
                        // How ProcessRunner surfaces a whisper-cli killed by SIGTERM (item 34): a cancellation.
                        fixture.transcriber.failNext = CancellationException("process was terminated by SIGTERM (exit 143)")
                        secondChunk.complete(Unit)

                        // The task keeps its queue position, so it runs again before the next task.
                        fixture.completed.receive() shouldBe task
                        fixture.completed.receive() shouldBe next
                        statusesOf(fixture, task) shouldBe
                            listOf(TaskStatus.RUNNING, TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.COMPLETED)
                        drain(fixture.failed).isEmpty().shouldBeTrue()
                        storedTaskOf(fixture, task).error.shouldBeNull()
                        fixture.transcriber.audioFiles
                            .filter { taskIdOf(it.parent) == task }
                            .map { it.fileName.toString() } shouldBe listOf("chunk-0.flac", "chunk-1.flac", "chunk-1.flac")
                    }
                }
            }
        }
        given("a task whose diarization's ffmpeg is killed by SIGTERM, and the app keeps running") {
            `when`("the engine runs it and the next queued task") {
                then("the task goes back to QUEUED, never FAILED, and the engine runs it again") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task =
                            enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1, speakersMode = SpeakersMode.AUTO)
                        val next = enqueueTask(fixture.tasks, "next.wav", queuePosition = 1, createdAt = 2)
                        // A segment to label, so the task diarizes.
                        fixture.transcriber.segmentsByTask =
                            mapOf(task to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello ")))
                        val diarizing = CompletableDeferred<Unit>()
                        fixture.diarizer.hold = diarizing

                        fixture.engine.start()
                        fixture.diarizer.entered.receive() shouldBe task
                        // How ProcessRunner surfaces the PCM decoder's ffmpeg killed by SIGTERM (item 34): a cancellation.
                        fixture.diarizer.failNext = CancellationException("process was terminated by SIGTERM (exit 143)")
                        diarizing.complete(Unit)

                        // The task keeps its queue position, so it runs again before the next task.
                        fixture.completed.receive() shouldBe task
                        fixture.completed.receive() shouldBe next
                        statusesOf(fixture, task) shouldBe
                            listOf(TaskStatus.RUNNING, TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.COMPLETED)
                        drain(fixture.failed).isEmpty().shouldBeTrue()
                        storedTaskOf(fixture, task).error.shouldBeNull()
                        fixture.diarizer.diarized shouldBe listOf(task, task)
                        // Its one chunk was DONE before the diarization: the rerun does not transcribe it again.
                        fixture.transcriber.audioFiles
                            .filter { taskIdOf(it.parent) == task }
                            .map { it.fileName.toString() } shouldBe listOf("chunk-0.flac")
                    }
                }
            }
        }
        given("an app that quits while a task transcribes its second chunk") {
            `when`("the app starts again") {
                then("the task was left RUNNING at the quit, and the new start queues it and runs it again from its interrupted chunk") {
                    withEngineDatabase { dataDir, db ->
                        // The application's engine scope (EngineModule): a SupervisorJob that quitting cancels.
                        val engineJob = SupervisorJob(backgroundScope.coroutineContext[Job])
                        val quitting = engineUnderTest(dataDir, db, CoroutineScope(backgroundScope.coroutineContext + engineJob))
                        quitting.chunker.chunkCount = 2
                        val task = enqueueTask(quitting.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        quitting.transcriber.hold = CompletableDeferred()
                        quitting.transcriber.holdFrom = 2

                        quitting.engine.start()
                        nextChunkEntered(quitting.transcriber) shouldBe "chunk-0.flac"
                        nextChunkEntered(quitting.transcriber) shouldBe "chunk-1.flac"
                        engineJob.cancelAndJoin()

                        // The quit killed the process and wrote nothing: the task is RUNNING until the next start.
                        quitting.transcriber.cancelled.map { it.fileName.toString() } shouldBe listOf("chunk-1.flac")
                        val quit = storedTaskOf(quitting, task)
                        quit.status shouldBe TaskStatus.RUNNING
                        quit.error.shouldBeNull()

                        val restarted = engineUnderTest(dataDir, db)
                        restarted.engine.start()

                        restarted.completed.receive() shouldBe task
                        restarted.transcriber.audioFiles.map { it.fileName.toString() } shouldBe listOf("chunk-1.flac")
                        drain(restarted.failed).isEmpty().shouldBeTrue()
                    }
                }
            }
        }
        given("a queued task whose RUNNING write is refused") {
            `when`("the engine starts it") {
                then("it is FAILED with the write's error and the worker runs the next queued task") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val first = enqueueTask(fixture.tasks, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(fixture.tasks, "second.wav", queuePosition = 1, createdAt = 2)
                        // The database refuses to mark the first task RUNNING, as a failing disk would.
                        db.writeTransaction {
                            exec(
                                "CREATE TRIGGER refuse_running BEFORE UPDATE OF status ON tasks " +
                                    "WHEN NEW.status = 'RUNNING' AND OLD.id = $first " +
                                    "BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END",
                            )
                        }

                        fixture.engine.start()

                        fixture.failed.receive() shouldBe first
                        val failed = storedTaskOf(fixture, first)
                        failed.status shouldBe TaskStatus.FAILED
                        failed.error shouldContain "disk I/O error"
                        failed.finishedAt.shouldNotBeNull()
                        fixture.completed.receive() shouldBe second
                    }
                }
            }
        }
    })
