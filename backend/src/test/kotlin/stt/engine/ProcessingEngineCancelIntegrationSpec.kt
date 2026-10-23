package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.plugins.di.dependencies
import stt.Integration
import stt.db.Database
import stt.db.TaskPhase
import stt.db.TaskRepository
import stt.queue.TaskStatus
import stt.transcribe.transcriptFixtures
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Roadmap item 6: cancel and delete stop the run on Windows, where the direct child is killed without
 * signals, and leave no whisper-cli or ffmpeg process behind.
 *
 * The clip is the 11 s jfk fixture looped 30 times (330 s), so the task stays in TRANSCRIBING long
 * enough to cancel it while a real whisper-cli process is running. On a host with process groups the
 * whole tree is killed; on Windows the direct child is destroyed and waited for. Either way the test
 * JVM must have no descendant whose executable is whisper-cli or ffmpeg once the command returns.
 */
class ProcessingEngineCancelIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("the application with the real ffmpeg, whisper-cli (ggml-tiny.en) and CAM++ model") {
            `when`("a task transcribing a long clip is canceled") {
                then("the run stops as CANCELED and no whisper-cli or ffmpeg process survives") {
                    val (_, jfk) = transcriptFixtures()
                    val clip = loopWav(jfk, loops = 30)
                    realEngineApp {
                        val db = application.dependencies.resolve<Database>()
                        val tasks = TaskRepository(db)
                        val id = tasks.upload("cancel.wav", clip)
                        val engine = application.dependencies.resolve<ProcessingEngine>()
                        engine.wake()

                        awaitUntil(timeout = 2.minutes, every = 100.milliseconds) {
                            tasks.find(id)?.task?.phase == TaskPhase.TRANSCRIBING && runningTools().isNotEmpty()
                        }

                        engine.cancelTask(id) shouldBe TaskCommandOutcome.Accepted
                        tasks.find(id)?.task?.status shouldBe TaskStatus.CANCELED
                        awaitUntil(timeout = 10.seconds, every = 100.milliseconds) { runningTools().isEmpty() }
                        runningTools().shouldBeEmpty()
                    }
                }
            }

            `when`("a task transcribing a long clip is deleted") {
                then("its row is gone and no whisper-cli or ffmpeg process survives") {
                    val (_, jfk) = transcriptFixtures()
                    val clip = loopWav(jfk, loops = 30)
                    realEngineApp {
                        val db = application.dependencies.resolve<Database>()
                        val tasks = TaskRepository(db)
                        val id = tasks.upload("delete.wav", clip)
                        val engine = application.dependencies.resolve<ProcessingEngine>()
                        engine.wake()

                        awaitUntil(timeout = 2.minutes, every = 100.milliseconds) {
                            tasks.find(id)?.task?.phase == TaskPhase.TRANSCRIBING && runningTools().isNotEmpty()
                        }

                        engine.deleteTask(id) shouldBe TaskCommandOutcome.Accepted
                        tasks.find(id).shouldBeNull()
                        awaitUntil(timeout = 10.seconds, every = 100.milliseconds) { runningTools().isEmpty() }
                        runningTools().shouldBeEmpty()
                    }
                }
            }
        }
    })
