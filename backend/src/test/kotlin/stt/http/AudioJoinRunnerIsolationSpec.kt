package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.audio.ExportedChunk
import stt.audio.PlannedChunk
import stt.audio.sineFlac
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask
import stt.process.ProcessRunner
import stt.queue.TaskStatus
import kotlin.time.Duration.Companion.seconds

/**
 * Item 37: the joiner's ffmpeg run used to resolve the engine's single-flight [ProcessRunner] (both shared
 * one DI instance), so `/api/tasks/{id}/audio` answered 500 "A run is already in flight" whenever the
 * engine held the runner with a task process. The joiner serves HTTP, not engine tasks: it needs its own
 * execution path, with the engine's pause/cancel on its runner untouched (ADR 0020).
 */
class AudioJoinRunnerIsolationSpec :
    BehaviorSpec({
        tags(Integration)

        given("the engine's ProcessRunner holds a transcription run") {
            `when`("a COMPLETED task's audio is requested") {
                then("200 audio/flac serves the joined FLAC, never 500") {
                    val dataDir = tempdir("stt-join-engine-busy").toPath()
                    val chunk0 = sineFlac(dataDir, "chunk-0", "440", "0.3")
                    val chunk1 = sineFlac(dataDir, "chunk-1", "880", "0.5")

                    val id =
                        Database.open(dataDir).use { db ->
                            val workDirectories = TaskWorkDirectories(dataDir)
                            val stored =
                                TaskRepository(db, workDirectories).createFromUpload(
                                    queuedTask("done.wav", createdAt = 1).copy(
                                        queuePosition = 0,
                                        status = TaskStatus.COMPLETED,
                                        finishedAt = 1_000,
                                    ),
                                    "audio bytes".toByteArray().inputStream(),
                                )
                            ChunkRepository(db).storePlan(
                                stored,
                                listOf(
                                    ExportedChunk(PlannedChunk(0L, 300L), chunk0),
                                    ExportedChunk(PlannedChunk(300L, 800L), chunk1),
                                ),
                            )
                            // The finished state (item 11): the work directory is gone.
                            workDirectories.workDir(stored).toFile().deleteRecursively()
                            stored
                        }

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()

                        // The engine's runner — the same instance the joiner used to resolve. Hold it with a
                        // process, as a transcription does, and mark the run claimed.
                        val engineRunner = application.dependencies.resolve<ProcessRunner>()
                        coroutineScope {
                            val started = CompletableDeferred<Unit>()
                            val engineRun =
                                launch {
                                    engineRunner.run(
                                        listOf("sh", "-c", "echo busy; sleep 60"),
                                        onStdoutLine = { started.complete(Unit) },
                                    )
                                }
                            try {
                                withTimeout(30.seconds) { started.await() }
                                val whole = client.get("/api/tasks/$id/audio")
                                whole.status shouldBe HttpStatusCode.OK
                                whole.contentType()?.withoutParameters() shouldBe ContentType.parse("audio/flac")
                                val body = whole.bodyAsBytes()
                                String(body, 0, 4, Charsets.UTF_8) shouldBe "fLaC"
                            } finally {
                                engineRun.cancelAndJoin()
                            }
                        }
                    }
                }
            }
        }
    })