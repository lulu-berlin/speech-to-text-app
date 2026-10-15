package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.queue.TaskStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class EngineShutdownNormalStopSpec :
    BehaviorSpec({
        given("a database left by a crash: the queue paused and a task RUNNING") {
            `when`("the application stops while the engine's worker is running") {
                then("the worker is joined and the Database closes cleanly") {
                    val dataDir = tempdir("stt-data").toPath()
                    val id =
                        Database.open(dataDir).use { db ->
                            QueueStateRepository(db).setPaused(true)
                            TaskRepository(db).insert(
                                queuedTask("crashed.wav", createdAt = 1).copy(
                                    status = TaskStatus.RUNNING,
                                    queuePosition = 0,
                                    startedAt = 2,
                                ),
                            )
                        }
                    lateinit var db: Database
                    // An engine worker that escapes on a closed Database is the bug this item fixes.
                    val workerEscaped = AtomicBoolean(false)

                    testApplication(CoroutineExceptionHandler { _, _ -> workerEscaped.set(true) }) {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()
                        db = application.dependencies.resolve<Database>()
                        val tasks = TaskRepository(db)

                        // Nothing in the test touches the engine: only the app's startup can recover the
                        // task. The paused queue keeps it from starting, so no real ffmpeg runs.
                        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
                        while (tasks.find(id)?.task?.status != TaskStatus.QUEUED && deadline.hasNotPassedNow()) {
                            delay(20.milliseconds)
                        }
                        tasks.find(id)?.task?.status shouldBe TaskStatus.QUEUED
                        // The stop runs while the worker is alive; the engine's cleanup must join it
                        // before the Database closes.
                    }

                    workerEscaped.get() shouldBe false
                    db.connection.isClosed shouldBe true
                }
            }
        }
    })
