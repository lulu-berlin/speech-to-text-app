package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.jsonPrimitive
import stt.Integration
import stt.awaitServiceServing
import stt.freePort
import stt.startService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.readText

/**
 * Roadmap item 66, test list item 9 (the secret's reach): the per-install secret lives in `config.json` only. It never
 * appears in the service's log or in the ephemeral `runtime.json`, and the browser enters through a one-time token
 * instead — a token that is not the secret either. Item 7's test covers the runtime directory as a whole; this one
 * covers what a person, a log collector or a backup that skipped the runtime directory could still read.
 */
class SecretHygieneIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a service that has launched the browser once") {
            `when`("its log and its runtime file are read afterwards") {
                then("the secret is in config.json and in neither of them, and the launch URL carried a token, not the secret") {
                    val temp = tempdir("stt-secret").toPath()
                    val dirs = ServiceDirs(temp.resolve("config"), temp.resolve("runtime"))
                    val log = temp.resolve("service.log")
                    val port = freePort()
                    val service = startService(temp, port, log, dirs)
                    try {
                        awaitServiceServing(port, dirs) shouldBe true
                        val secret = readConfig(dirs).getValue("secret").jsonPrimitive.content

                        val http = HttpClient.newHttpClient()
                        val launched =
                            http.send(
                                HttpRequest
                                    .newBuilder(URI.create("http://127.0.0.1:$port/api/launch-token"))
                                    .header("Authorization", "Bearer $secret")
                                    .POST(HttpRequest.BodyPublishers.noBody())
                                    .build(),
                                HttpResponse.BodyHandlers.ofString(),
                            )
                        launched.statusCode() shouldBe 200
                        val token = launched.body().substringAfter("\"token\":\"").substringBefore('"')

                        val opened =
                            http.send(
                                HttpRequest
                                    .newBuilder(URI.create("http://127.0.0.1:$port/open?t=$token"))
                                    .build(),
                                HttpResponse.BodyHandlers.discarding(),
                            )
                        opened.headers().firstValue("location").orElse("") shouldBe "/"

                        configFile(dirs).readText() shouldContain secret
                        runtimeFile(dirs).readText() shouldNotContain secret
                        log.readText() shouldNotContain secret
                        token shouldNotContain secret.substring(0, 8)
                    } finally {
                        service.destroyForcibly().waitFor()
                    }
                }
            }
        }
    })
