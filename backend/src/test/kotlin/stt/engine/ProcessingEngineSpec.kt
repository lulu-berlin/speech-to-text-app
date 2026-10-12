package stt.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import stt.db.SpeakersMode
import stt.engine.TaskCommandOutcome
import stt.queue.TaskStatus
import stt.transcribe.TranscriptionException
import stt.transcribe.WhisperSegment
import stt.withCapturedLogs
import java.nio.file.Files

class ProcessingEngineSpec :
    BehaviorSpec({
        given("two queued tasks") {
            `when`("the engine runs") {
                then("they are processed one at a time in queue_position order") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val tasks = fixture.tasks
                        val first = enqueueTask(tasks, fixture.workDirectories, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(tasks, fixture.workDirectories, "second.wav", queuePosition = 1, createdAt = 2)

                        fixture.engine.start()

                        fixture.completed.receive() shouldBe first
                        fixture.completed.receive() shouldBe second

                        fixture.chunker.workDirs.map(::taskIdOf) shouldBe listOf(first, second)
                        fixture.transcriber.audioFiles.map { taskIdOf(it.parent) } shouldBe listOf(first, second)
                        fixture.chunker.probe.max shouldBe 1
                        fixture.transcriber.probe.max shouldBe 1
                        tasks.find(first)?.task?.status shouldBe TaskStatus.COMPLETED
                        tasks.find(second)?.task?.status shouldBe TaskStatus.COMPLETED
                    }
                }
            }
        }

        given("a completed task whose work directory still exists") {
            `when`("deleting it") {
                then("the task row, its cached audio and its work directory are gone") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        // enqueueTask seeds `work/<id>/source`, the state a COMPLETED task's prune leaves
                        // (ADR 0024). The engine is not started, so only the delete path can remove it.
                        val task = enqueueTask(fixture.tasks, fixture.workDirectories, "doomed.wav", queuePosition = 0, createdAt = 1)
                        fixture.tasks.setCompleted(task, finishedAt = 1_000L)

                        fixture.engine.deleteTask(task) shouldBe TaskCommandOutcome.Accepted

                        fixture.tasks.find(task) shouldBe null
                        Files.exists(fixture.workDirectories.workDir(task)).shouldBeFalse()
                        Files.exists(fixture.workDirectories.cachedAudio(task)).shouldBeFalse()
                    }
                }
            }
        }

        given("three queued tasks of which the first is running") {
            `when`("the queue is reordered while that task runs") {
                then("the running task is not interrupted and the new order decides what starts next") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val tasks = fixture.tasks
                        val first = enqueueTask(tasks, fixture.workDirectories, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(tasks, fixture.workDirectories, "second.wav", queuePosition = 1, createdAt = 2)
                        val third = enqueueTask(tasks, fixture.workDirectories, "third.wav", queuePosition = 2, createdAt = 3)

                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release
                        fixture.engine.start()

                        // The worker is inside the first task's transcription; reorder behind its back.
                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe first
                        tasks.setQueuePositions(listOf(third, second, first))
                        fixture.engine.wake()
                        fixture.transcriber.hold = null
                        release.complete(Unit)

                        fixture.completed.receive() shouldBe first
                        fixture.completed.receive() shouldBe third
                        fixture.completed.receive() shouldBe second
                        fixture.chunker.workDirs.map(::taskIdOf) shouldBe listOf(first, third, second)
                    }
                }
            }
        }

        given("a task being processed by the worker") {
            `when`("a log line is emitted from inside its job") {
                then("the log event's MDC map carries the task's id, in every line the job emits") {
                    withEngineDatabase { dataDir, db ->
                        withCapturedLogs("stt.engine.FakeTranscriber", Level.INFO) { events ->
                            val fixture = engineUnderTest(dataDir, db)
                            val task =
                                enqueueTask(fixture.tasks, fixture.workDirectories, "tagged.wav", queuePosition = 0, createdAt = 1)
                            fixture.engine.start()
                            fixture.completed.receive() shouldBe task

                            // The line the fake transcriber logs while the task runs: the id is a property
                            // of the log record, not text in the message (item 46).
                            assertTaskIdOnEveryEvent(events, task)
                        }
                    }
                }
            }
        }

        given("a task whose work runs across the dispatchers") {
            `when`("the chunk's IO block and the embedding loop log") {
                then("both lines carry the task's id: the MDC travelled with the coroutine across the switches") {
                    withEngineDatabase { dataDir, db ->
                        withCapturedLogs("stt.engine.FakeDiarizer", Level.INFO) { diarizerEvents ->
                            withCapturedLogs("stt.engine.FakeTranscriber", Level.INFO) { transcriberEvents ->
                                val fixture = engineUnderTest(dataDir, db)
                                val task =
                                    enqueueTask(
                                        fixture.tasks,
                                        fixture.workDirectories,
                                        "switching.wav",
                                        queuePosition = 0,
                                        createdAt = 1,
                                        speakersMode = SpeakersMode.AUTO,
                                    )
                                fixture.transcriber.segmentsByTask =
                                    mapOf(task to listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = "hello")))
                                fixture.engine.start()
                                fixture.completed.receive() shouldBe task

                                // The transcriber logs from inside Dispatchers.IO, the diarizer from inside
                                // Dispatchers.Default (item 46): both ran and both events carry the id.
                                transcriberEvents.shouldNotBeEmpty()
                                diarizerEvents.shouldNotBeEmpty()
                                assertTaskIdOnEveryEvent(transcriberEvents + diarizerEvents, task)
                            }
                        }
                    }
                }
            }
        }

        given("a task whose progress is reported from a reader coroutine") {
            `when`("the reader logs while the task runs") {
                then("the reader's event carries the task's id: the child coroutine inherited the context") {
                    withEngineDatabase { dataDir, db ->
                        withCapturedLogs("stt.engine.FakeTranscriber", Level.INFO) { events ->
                            val fixture = engineUnderTest(dataDir, db)
                            val task =
                                enqueueTask(fixture.tasks, fixture.workDirectories, "reader.wav", queuePosition = 0, createdAt = 1)
                            fixture.transcriber.progressScript = listOf(0.5, 1.0)
                            fixture.engine.start()
                            fixture.completed.receive() shouldBe task

                            fixture.transcriber.progressReaderLogged shouldBe true
                            assertTaskIdOnEveryEvent(events, task)
                        }
                    }
                }
            }
        }

        given("a task that fails while being processed") {
            `when`("its chunks throw") {
                then("the failure path's logged lines carry the task's id, and the task lands FAILED") {
                    withEngineDatabase { dataDir, db ->
                        withCapturedLogs("stt.engine.FakeTranscriber", Level.INFO) { events ->
                            val fixture = engineUnderTest(dataDir, db)
                            val task =
                                enqueueTask(fixture.tasks, fixture.workDirectories, "failing.wav", queuePosition = 0, createdAt = 1)
                            fixture.transcriber.failNext = TranscriptionException("boom")
                            fixture.engine.start()

                            fixture.failed.receive() shouldBe task
                            fixture.tasks
                                .find(task)
                                ?.task
                                ?.status shouldBe TaskStatus.FAILED

                            // The engine does not log the failure itself (the error is stored and published);
                            // every line the run did emit — the fake's, before the throw — is inside the
                            // task's tag, and the FAILED terminal was reached within the span (item 46).
                            assertTaskIdOnEveryEvent(events, task)
                        }
                    }
                }
            }
        }
    })

/** At least one event was captured, and every one carries [task]'s id in its MDC map (item 46). */
private fun assertTaskIdOnEveryEvent(
    events: List<ILoggingEvent>,
    task: Long,
) {
    events.shouldNotBeEmpty()
    events.forEach { event -> event.getMDCPropertyMap()["taskId"] shouldBe task.toString() }
}
