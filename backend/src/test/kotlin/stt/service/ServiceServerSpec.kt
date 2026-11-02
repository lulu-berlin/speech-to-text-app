package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldNotBeExactly
import io.kotest.matchers.shouldBe
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Roadmap item 66, test-list D5: the service serves on its durable port, and when another program holds that port the
 * bind itself falls back to a free one, so another program taking the port between the service's check and its bind
 * cannot make the start fail.
 */
class ServiceServerSpec :
    BehaviorSpec({
        given("a durable port another program holds when the service binds it") {
            `when`("the service's server is started on it") {
                then("it serves on a free port instead") {
                    ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { foreign ->
                        val taken = foreign.localPort
                        val server = startOnDurablePort(taken, wait = false, ::answering)
                        try {
                            val port = boundPort(server)
                            port shouldNotBeExactly taken
                            get(port) shouldBe "service"
                        } finally {
                            server.stop(0, 0)
                        }
                    }
                }
            }
        }

        given("a durable port nobody holds") {
            `when`("the service's server is started on it") {
                then("it serves on that port") {
                    val free = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
                    val server = startOnDurablePort(free, wait = false, ::answering)
                    try {
                        boundPort(server) shouldBe free
                        get(free) shouldBe "service"
                    } finally {
                        server.stop(0, 0)
                    }
                }
            }
        }
    })

/** A server on [port] that answers `GET /` with `service`, standing in for the app. */
private fun answering(port: Int): EmbeddedServer<*, *> =
    embeddedServer(Netty, host = "127.0.0.1", port = port) {
        routing { get("/") { call.respondText("service") } }
    }

private fun boundPort(server: EmbeddedServer<*, *>): Int =
    runBlocking {
        server.engine
            .resolvedConnectors()
            .single()
            .port
    }

private fun get(port: Int): String =
    HttpClient
        .newHttpClient()
        .send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/")).build(), HttpResponse.BodyHandlers.ofString())
        .body()
