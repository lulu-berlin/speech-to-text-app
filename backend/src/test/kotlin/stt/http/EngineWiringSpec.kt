package stt.http

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.engine.ProcessingEngine
import stt.executeSql
import stt.queue.TaskStatus
import stt.withCapturedLogs
import java.sql.SQLException
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
                            TaskRepository(db).insert(
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
                        val tasks = TaskRepository(db)

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
        given("a database the engine cannot recover: its recovery write is refused") {
            `when`("the application starts") {
                then("startup fails naming the logged error, so no worker serves it") {
                    val dataDir = tempdir("stt-data").toPath()
                    Database.open(dataDir).use { db ->
                        // A task a crash left RUNNING, so the recovery's UPDATE has a row to hit.
                        TaskRepository(db).insert(
                            queuedTask("crashed.wav", createdAt = 1).copy(
                                status = TaskStatus.RUNNING,
                                queuePosition = 0,
                                startedAt = 2,
                            ),
                        )
                    }
                    // A trigger that refuses every recovery write.
                    executeSql(
                        dataDir,
                        "CREATE TRIGGER block_recovery BEFORE UPDATE ON tasks " +
                            "BEGIN SELECT RAISE(ABORT, 'recovery blocked'); END",
                    )
                    var logged: List<ILoggingEvent>? = null
                    val error =
                        shouldThrow<SQLException> {
                            testApplication {
                                application { sttApp(stt.config.testConfig(dataDir)) }
                                withCapturedLogs("stt.engine.ProcessingEngine", Level.ERROR) { events ->
                                    logged = events
                                    startApplication()
                                }
                            }
                        }

                    error.message shouldContain "recovery blocked"
                    val events = logged.shouldNotBeNull()
                    events.map { it.level }.shouldContain(Level.ERROR)
                    events.map { it.formattedMessage }.any { it.contains("Could not recover") } shouldBe true
                    events.mapNotNull { it.throwableProxy?.message }.any { it.contains("recovery blocked") } shouldBe true
                }
            }
        }
    })
