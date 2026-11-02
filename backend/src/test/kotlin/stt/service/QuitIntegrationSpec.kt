package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonPrimitive
import stt.Integration
import stt.awaitServiceServing
import stt.freePort
import stt.startService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Roadmap item 66, test-list item 4 (the quit half): quitting the service is explicit. In app mode `POST /api/quit`,
 * with the per-install secret, is answered `202` and stops the service cleanly — it exits, and `runtime.json` goes
 * with it (D3), while `config.json` stays for the next start. `stt-app quit` and the window use this route.
 */
class QuitIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a running service") {
            `when`("a client sends POST /api/quit with the per-install secret") {
                then("it answers 202, exits, and removes runtime.json while config.json stays") {
                    val dirs = ServiceDirs(tempdir("stt-quit-config").toPath(), tempdir("stt-quit-runtime").toPath())
                    val port = freePort()
                    val service =
                        startService(
                            tempdir("stt-quit-data").toPath(),
                            port,
                            tempdir("stt-quit-logs").toPath().resolve("service.log"),
                            dirs,
                        )
                    try {
                        awaitServiceServing(port, dirs) shouldBe true
                        val secret = readConfig(dirs).getValue("secret").jsonPrimitive.content

                        val response =
                            HttpClient
                                .newHttpClient()
                                .send(
                                    HttpRequest
                                        .newBuilder(URI("http://127.0.0.1:$port/api/quit"))
                                        .header("Authorization", "Bearer $secret")
                                        .POST(HttpRequest.BodyPublishers.noBody())
                                        .build(),
                                    HttpResponse.BodyHandlers.ofString(),
                                )

                        response.statusCode() shouldBe 202
                        service.waitFor(30, TimeUnit.SECONDS) shouldBe true
                        runtimeFile(dirs).exists() shouldBe false
                        configFile(dirs).exists() shouldBe true
                    } finally {
                        service.destroyForcibly().waitFor()
                    }
                }
            }
        }
    })
