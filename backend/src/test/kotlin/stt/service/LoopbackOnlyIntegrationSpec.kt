package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import stt.Integration
import stt.awaitServiceServing
import stt.freePort
import stt.startService
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

/**
 * Roadmap item 66, test list item 9 (the trust boundary's socket): app mode listens on this machine's loopback only,
 * whatever `STT_HOST` says (dev/server mode keeps `STT_HOST`). The service answers on `127.0.0.1`, and a connection to
 * the machine's own non-loopback address on the same port is refused, so nothing on the LAN can reach it. A machine with
 * no second address cannot show the refusal and skips it.
 */
class LoopbackOnlyIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("the service started with STT_HOST=0.0.0.0, dev mode's default") {
            `when`("a client connects on loopback and on the machine's own non-loopback address") {
                then("loopback answers with the credential and the non-loopback address refuses the connection") {
                    val lan = nonLoopbackAddress()
                    if (lan != null) {
                        val temp = tempdir("stt-loopback").toPath()
                        val dirs = ServiceDirs(temp.resolve("config"), temp.resolve("runtime"))
                        val port = freePort()
                        val service =
                            startService(temp, port, temp.resolve("service.log"), dirs, env = mapOf("STT_HOST" to "0.0.0.0"))
                        try {
                            awaitServiceServing(port, dirs) shouldBe true

                            reaches("127.0.0.1", port) shouldBe true
                            reaches(lan, port) shouldBe false
                        } finally {
                            service.destroyForcibly().waitFor()
                        }
                    }
                }
            }
        }
    })

/** The machine's own IPv4 address that is not a loopback one, or null when it has none. */
private fun nonLoopbackAddress(): String? =
    NetworkInterface
        .networkInterfaces()
        .toList()
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress

/** Whether a TCP connection to [host]:[port] can be made at all. */
private fun reaches(
    host: String,
    port: Int,
): Boolean =
    try {
        HttpClient
            .newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build()
            .send(
                HttpRequest
                    .newBuilder(URI.create("http://$host:$port/api/health"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        true
    } catch (e: IOException) {
        false
    }
