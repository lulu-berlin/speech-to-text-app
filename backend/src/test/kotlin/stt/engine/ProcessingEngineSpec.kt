package stt.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.db.SpeakersMode
import stt.engine.TaskCommandOutcome
import stt.process.ProcessRunner
import stt.queue.TaskStatus
import stt.transcribe.DeviceProbe
import stt.transcribe.TranscriptionException
import stt.transcribe.WhisperSegment
import stt.withCapturedLogs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class ProcessingEngineSpec :
    BehaviorSpec({
        given("two queued tasks") {
            `when`("the engine runs") {
                then("they are processed one at a time in queue_position order") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val tasks = fixture.tasks
                        val first = enqueueTask(tasks, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(tasks, "second.wav", queuePosition = 1, createdAt = 2)

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

        given("a completed task") {
            `when`("deleting it") {
                then("the task row is gone") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "doomed.wav", queuePosition = 0, createdAt = 1)
                        fixture.tasks.setCompleted(task, finishedAt = 1_000L)

                        fixture.engine.deleteTask(task) shouldBe TaskCommandOutcome.Accepted

                        fixture.tasks.find(task) shouldBe null
                        Files.exists(dataDir.dataDir.resolve("work")).shouldBeFalse()
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
                        val first = enqueueTask(tasks, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(tasks, "second.wav", queuePosition = 1, createdAt = 2)
                        val third = enqueueTask(tasks, "third.wav", queuePosition = 2, createdAt = 3)

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
                                enqueueTask(fixture.tasks, "tagged.wav", queuePosition = 0, createdAt = 1)
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
                                enqueueTask(fixture.tasks, "reader.wav", queuePosition = 0, createdAt = 1)
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
                                enqueueTask(fixture.tasks, "failing.wav", queuePosition = 0, createdAt = 1)
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

        given("two tasks processed one after the other") {
            `when`("a coroutine outside the task jobs logs while the first task is suspended and after both completed") {
                then("its lines carry no taskId key, and each task's lines carry only that task's id") {
                    withEngineDatabase { dataDir, db ->
                        withCapturedLogs(OUTSIDE_THE_TASK_JOBS, Level.INFO) { outsideEvents ->
                            withCapturedLogs("stt.engine.FakeTranscriber", Level.INFO) { transcriberEvents ->
                                val fixture = engineUnderTest(dataDir, db)
                                val first =
                                    enqueueTask(fixture.tasks, "first.wav", queuePosition = 0, createdAt = 1)
                                val second =
                                    enqueueTask(fixture.tasks, "second.wav", queuePosition = 1, createdAt = 2)
                                val hold = CompletableDeferred<Unit>()
                                fixture.transcriber.hold = hold
                                fixture.engine.start()

                                // The first task's job is suspended inside its transcription; a coroutine of the
                                // engine's scope that is not a task job logs meanwhile (item 46: no leakage).
                                taskIdOf(
                                    fixture.transcriber.entered
                                        .receive()
                                        .parent,
                                ) shouldBe first
                                logOutsideTheTaskJobs("while the first task is suspended")
                                hold.complete(Unit)
                                fixture.completed.receive() shouldBe first
                                fixture.completed.receive() shouldBe second
                                logOutsideTheTaskJobs("after both tasks completed")

                                outsideEvents.map { it.formattedMessage } shouldBe
                                    listOf("while the first task is suspended", "after both tasks completed")
                                outsideEvents.forEach { event -> event.getMDCPropertyMap() shouldNotContainKey "taskId" }
                                // Every fake-transcriber line names the audio it works on, <dataDir>/work/<id>/…:
                                // its tag is that task's id, never the other's.
                                val taskOfLine = { event: ILoggingEvent -> taskIdOf((event.argumentArray[0] as Path).parent) }
                                transcriberEvents.map(taskOfLine).toSet() shouldBe setOf(first, second)
                                transcriberEvents.forEach { event ->
                                    event.getMDCPropertyMap()["taskId"] shouldBe taskOfLine(event).toString()
                                }
                            }
                        }
                    }
                }
            }
        }

        given("two queued tasks, named in coroutine dumps") {
            `when`("the worker processes them") {
                then("each task's work runs in a coroutine named task-<its id>") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val first = enqueueTask(fixture.tasks, "first.wav", queuePosition = 0, createdAt = 1)
                        val second = enqueueTask(fixture.tasks, "second.wav", queuePosition = 1, createdAt = 2)
                        fixture.engine.start()
                        fixture.completed.receive() shouldBe first
                        fixture.completed.receive() shouldBe second

                        // Observed from inside the task's transcription call (item 46): the name the coroutine
                        // debugger and dumps show for the task's job.
                        fixture.transcriber.coroutineNames shouldBe listOf("task-$first", "task-$second")
                    }
                }
            }
        }

        given("one queued task and a device probe that fails while the task is suspended") {
            `when`("the engine starts and the probe runs") {
                then("the probe's warning carries no taskId key; the task's own lines carry its id") {
                    withEngineDatabase { dataDir, db ->
                        withCapturedLogs("stt.transcribe.DeviceProbe") { probeWarnings ->
                            withCapturedLogs("stt.engine.FakeTranscriber", Level.INFO) { transcriberEvents ->
                                val fixture = engineUnderTest(dataDir, db)
                                val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                                val hold = CompletableDeferred<Unit>()
                                fixture.transcriber.hold = hold
                                fixture.engine.start()
                                taskIdOf(
                                    fixture.transcriber.entered
                                        .receive()
                                        .parent,
                                ) shouldBe task

                                // The task's job is suspended; the probe (no whisper-cli: the spawn throws,
                                // no process starts) runs in a coroutine of the engine's scope.
                                val probe =
                                    DeviceProbe(
                                        ProcessRunner(2.seconds) { throw IOException("no whisper-cli") },
                                        Path.of("/m.bin"),
                                        "/whisper-cli",
                                    )
                                backgroundScope.launch { probe.gpus() }.join()
                                hold.complete(Unit)
                                fixture.completed.receive() shouldBe task

                                probeWarnings.map { it.formattedMessage.substringBefore(";") } shouldBe
                                    listOf("Device probe failed")
                                probeWarnings.forEach { event ->
                                    event.getMDCPropertyMap() shouldNotContainKey "taskId"
                                }
                                assertTaskIdOnEveryEvent(transcriberEvents, task)
                            }
                        }
                    }
                }
            }
        }

        given("a started engine with one queued task") {
            `when`("the task is held mid-transcription, then completes") {
                then("the worker runs as engine-worker beside task-<id>, and stays after the task's job is gone") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val task = enqueueTask(fixture.tasks, "task.wav", queuePosition = 0, createdAt = 1)
                        val hold = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = hold
                        fixture.engine.start()
                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe task

                        // The names a coroutine dump shows for the engine's scope (item 46).
                        namedEngineCoroutines().toSet() shouldBe setOf("engine-worker", "task-$task")

                        hold.complete(Unit)
                        fixture.completed.receive() shouldBe task
                        pumpUntil(5.seconds) { namedEngineCoroutines() == listOf("engine-worker") }
                        namedEngineCoroutines() shouldBe listOf("engine-worker")
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

/** The logger of [logOutsideTheTaskJobs]: a task-agnostic coroutine's lines (item 46's no-leakage test). */
private const val OUTSIDE_THE_TASK_JOBS = "stt.engine.OutsideTheTaskJobs"

private val outsideTheTaskJobs: Logger = LoggerFactory.getLogger(OUTSIDE_THE_TASK_JOBS)

/** Logs [message] from a coroutine of the engine's scope that is not a task job, and waits until it has. */
private suspend fun TestScope.logOutsideTheTaskJobs(message: String) {
    backgroundScope.launch { outsideTheTaskJobs.info(message) }.join()
}

/** The names of the live coroutines of the engine's scope that have one (each launched job is its own scope). */
private fun TestScope.namedEngineCoroutines(): List<String> =
    backgroundScope.coroutineContext.job.children
        .mapNotNull { (it as CoroutineScope).coroutineContext[CoroutineName]?.name }
        .toList()
