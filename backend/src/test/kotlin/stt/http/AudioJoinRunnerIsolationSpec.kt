package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.process.ProcessRunner
import java.util.concurrent.atomic.AtomicInteger
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
                    val id = seedCompletedTaskWithChunks(dataDir)

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

        given("two COMPLETED tasks with stored chunk audio") {
            `when`("their audio is requested simultaneously") {
                then("both joins succeed: 200 audio/flac each") {
                    val dataDir = tempdir("stt-join-concurrent").toPath()
                    val idA = seedCompletedTaskWithChunks(dataDir, "done-a")
                    val idB = seedCompletedTaskWithChunks(dataDir, "done-b")

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()

                        // Each join runs its own fresh ProcessRunner (item 37): neither may contend with the
                        // other, or one would answer 500 "A run is already in flight".
                        val responses =
                            coroutineScope {
                                listOf(idA, idB)
                                    .map { id -> async { client.get("/api/tasks/$id/audio") } }
                                    .awaitAll()
                            }
                        responses.forEach { response ->
                            response.status shouldBe HttpStatusCode.OK
                            response.contentType()?.withoutParameters() shouldBe ContentType.parse("audio/flac")
                            val body = response.bodyAsBytes()
                            String(body, 0, 4, Charsets.UTF_8) shouldBe "fLaC"
                        }
                    }
                }
            }
        }

        given("an engine process paused while a join runs") {
            `when`("a COMPLETED task's audio is requested during the pause") {
                then("the join serves 200, the pause holds, and resume continues the engine run") {
                    val dataDir = tempdir("stt-join-pause").toPath()
                    val id = seedCompletedTaskWithChunks(dataDir)

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }
                        startApplication()

                        // The engine's runner — its pause/cancel seam (EngineModule hands this instance to the
                        // engine as its ProcessControl). A counting loop keeps lines continuously pending, so
                        // a pause window can prove the run was really frozen while the join ran: the same
                        // determinism pattern as ProcessRunnerSpec.
                        val engineRunner = application.dependencies.resolve<ProcessRunner>()
                        val delivered = mutableListOf<String>()
                        val sawThree = CompletableDeferred<Unit>()
                        val threshold = AtomicInteger(Int.MAX_VALUE)
                        val nextAfterResume = CompletableDeferred<Int>()
                        coroutineScope {
                            val engineRun =
                                launch {
                                    engineRunner.run(
                                        listOf("sh", "-c", "i=0; while :; do i=\$((i+1)); echo \$i; sleep 0.05; done"),
                                        onStdoutLine = { line ->
                                            synchronized(delivered) { delivered.add(line) }
                                            if (line == "3") sawThree.complete(Unit)
                                            if (line.toInt() > threshold.get()) nextAfterResume.complete(line.toInt())
                                        },
                                    )
                                }
                            try {
                                withTimeout(30.seconds) { sawThree.await() }
                                engineRunner.pause()
                                val frozenCount = synchronized(delivered) { delivered.size }

                                // The join runs against the frozen engine run: its own runner is independent,
                                // so it serves — and it must neither wake the SIGSTOPped tree nor be frozen by it.
                                val whole = client.get("/api/tasks/$id/audio")
                                whole.status shouldBe HttpStatusCode.OK
                                whole.contentType()?.withoutParameters() shouldBe ContentType.parse("audio/flac")
                                String(whole.bodyAsBytes(), 0, 4, Charsets.UTF_8) shouldBe "fLaC"

                                // The pause held through the join: at most the line that was in flight when
                                // SIGSTOP landed (ProcessRunnerSpec's tolerance), not the ~20 the loop would
                                // have emitted.
                                (synchronized(delivered) { delivered.size } - frozenCount)
                                    .shouldBeLessThanOrEqualTo(1)

                                // Resuming continues the counting run seamlessly from where it froze.
                                threshold.set(synchronized(delivered) { delivered.last().toInt() })
                                engineRunner.resume()
                                withTimeout(10.seconds) { nextAfterResume.await() }.shouldBe(threshold.get() + 1)
                            } finally {
                                engineRun.cancelAndJoin()
                            }
                        }
                    }
                }
            }
        }
    })
