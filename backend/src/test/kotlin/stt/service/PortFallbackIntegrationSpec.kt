package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.ints.shouldNotBeExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import stt.Integration
import stt.afterPortChosen
import stt.awaitServiceServing
import stt.config.testConfig
import stt.freePort
import stt.http.TrayJson
import stt.runService
import stt.startService
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Roadmap item 66, test list item 6 (the service half): when a foreign program holds the port the service would take,
 * the service serves on a free port instead, persists it as its durable port in `config.json`, and advertises it in
 * `runtime.json`, the file the tray and `stt-app open` read (the client reading it is `client/stt-client`'s test).
 */
class PortFallbackIntegrationSpec :
    BehaviorSpec(
        {
            tags(Integration)

            /** A POST with the per-install secret; a null [body] sends none. */
            fun post(
                port: Int,
                secret: String,
                path: String,
                body: String?,
            ): Int =
                HttpClient
                    .newHttpClient()
                    .send(
                        HttpRequest
                            .newBuilder(URI("http://127.0.0.1:$port$path"))
                            .header("Authorization", "Bearer $secret")
                            .header("Content-Type", "application/json")
                            .POST(
                                if (body == null) {
                                    HttpRequest.BodyPublishers.noBody()
                                } else {
                                    HttpRequest.BodyPublishers.ofString(body)
                                },
                            ).build(),
                        HttpResponse.BodyHandlers.ofString(),
                    ).statusCode()

            /** Whether [file] appears within [timeout]. */
            suspend fun awaitCreated(
                file: Path,
                timeout: Duration = 20.seconds,
            ): Boolean {
                val deadline = System.nanoTime() + timeout.inWholeNanoseconds
                while (System.nanoTime() < deadline) {
                    if (file.exists()) return true
                    delay(100)
                }
                return false
            }

            given("a port held by a foreign program") {
                `when`("the service starts with that port as its port") {
                    then("it serves on a free port, persists it in config.json and advertises it in runtime.json") {
                        val dataDir = tempdir("stt-fallback-data").toPath()
                        val dirs = ServiceDirs(tempdir("stt-fallback-config").toPath(), tempdir("stt-fallback-runtime").toPath())
                        val logs = tempdir("stt-fallback-logs").toPath()
                        ServerSocket(0).use { foreign ->
                            val taken = foreign.localPort
                            val service = startService(dataDir, taken, logs.resolve("service.log"), dirs)
                            try {
                                awaitFile(runtimeFile(dirs)) shouldBe true
                                val endpoint = URI(readRuntime(dirs).getValue("endpoint").jsonPrimitive.content)
                                val actual = endpoint.port

                                actual shouldNotBeExactly taken
                                endpoint.host shouldBe "127.0.0.1"
                                awaitServiceServing(actual, dirs) shouldBe true
                                readConfig(dirs).getValue("port").jsonPrimitive.int shouldBe actual
                            } finally {
                                service.destroyForcibly().waitFor()
                            }
                        }
                    }
                }
            }

            given("the durable port taken between the pre-check and the bind, so only the bind's fallback can serve") {
                `when`("a dead tray registers, and the client sends POST /api/quit") {
                    then("the tray is restarted, and the fallback service exits and withdraws runtime.json") {
                        val dataDir = tempdir("stt-fallback-data").toPath()
                        val dirs = ServiceDirs(tempdir("stt-fallback-config").toPath(), tempdir("stt-fallback-runtime").toPath())
                        val marker = tempdir("stt-fallback-tray").toPath().resolve("restarted")
                        val chosen = freePort()
                        ServerSocket().use { foreign ->
                            // The pre-check finds the port free; this binds it before the first server's bind.
                            afterPortChosen = { port -> foreign.bind(InetSocketAddress("127.0.0.1", port)) }
                            val service =
                                thread(isDaemon = true) {
                                    runService(testConfig(dataDir).copy(port = chosen, portConfigured = true), dirs)
                                }
                            try {
                                awaitFile(runtimeFile(dirs)) shouldBe true
                                val endpoint = URI(readRuntime(dirs).getValue("endpoint").jsonPrimitive.content)
                                val port = endpoint.port
                                awaitServiceServing(port, dirs) shouldBe true
                                val secret = readConfig(dirs).getValue("secret").jsonPrimitive.content

                                // A tray that is already dead: the service must start its command anyway.
                                val tray =
                                    Json.encodeToString(
                                        TrayJson.serializer(),
                                        TrayJson(pid = Long.MAX_VALUE, command = listOf("sh", "-c", "printf restarted > $marker")),
                                    )
                                post(port, secret, "/api/tray", tray) shouldBe 202
                                awaitCreated(marker) shouldBe true

                                post(port, secret, "/api/quit", null) shouldBe 202
                                service.join(30_000)
                                service.isAlive shouldBe false
                                runtimeFile(dirs).exists() shouldBe false
                                configFile(dirs).exists() shouldBe true
                            } finally {
                                afterPortChosen = null
                                service.interrupt()
                            }
                        }
                    }
                }
            }
        },
    )
