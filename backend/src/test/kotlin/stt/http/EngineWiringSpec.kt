package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask
import stt.engine.ProcessingEngine
import stt.queue.TaskStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class EngineWiringSpec :
    BehaviorSpec({

        given("a database left by a crash: the queue paused and a task RUNNING") {
            `when`("the application starts") {
                then("the engine starts on its own and recovers the task to QUEUED, and DI provides it") {
                    val escaped = AtomicBoolean(false)
                    lateinit var db: Database
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            TaskRepository(db, TaskWorkDirectories(dataDir)).insert(
                                queuedTask("crashed.wav", createdAt = 1).copy(
                                    status = TaskStatus.RUNNING,
                                    queuePosition = 0,
                                    startedAt = 2,
                                ),
                            )
                        }

                    testApplication(CoroutineExceptionHandler { _, _ -> escaped.set(true) }) {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()
                        db = application.dependencies.resolve<Database>()
                        val tasks = TaskRepository(db, TaskWorkDirectories(dataDir))

                        // Nothing in the test touches the engine before this: only the app's startup can recover the task.
                        // The paused queue keeps the recovered task from starting, so no real ffmpeg runs.
                        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
                        while (tasks.find(id)?.task?.status != TaskStatus.QUEUED && deadline.hasNotPassedNow()) {
                            delay(20.milliseconds)
                        }
                        tasks.find(id)?.task?.status shouldBe TaskStatus.QUEUED

                        application.dependencies.resolve<ProcessingEngine>().shouldBeInstanceOf<ProcessingEngine>()
                    }

                    escaped.get().shouldBeFalse()
                    db.connection.isClosed.shouldBeTrue()
                }
            }
        }
    })
