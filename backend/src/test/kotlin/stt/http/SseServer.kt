package stt.http

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A real Netty server on an ephemeral port, running [module] — the in-process test host runs a request handler
 * to completion before delivering the response, which an open SSE stream never does, so the SSE specs talk to
 * real sockets. No external process: Netty runs in-JVM.
 */
internal class SseServer(
    module: Application.() -> Unit,
    private val http: HttpClient = HttpClient.newHttpClient(),
) : AutoCloseable {
    private val server =
        embeddedServer(Netty, port = 0, host = "127.0.0.1", module = module).also { it.start(wait = false) }

    val base: String =
        "http://127.0.0.1:" +
            runBlocking {
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            }

    private var stream: InputStream? = null

    /** One REST command with no body; its status, e.g. `command("POST", "/api/queue/pause")`. */
    fun command(
        method: String,
        path: String,
    ): Int = send(HttpRequest.newBuilder(URI("$base$path")).method(method, HttpRequest.BodyPublishers.noBody()))

    /** One REST command with a JSON [body]; its status, e.g. `command("PUT", "/api/tasks/1/speakers/0", """{…}""")`. */
    fun command(
        method: String,
        path: String,
        body: String,
    ): Int =
        send(
            HttpRequest
                .newBuilder(URI("$base$path"))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)),
        )

    private fun send(request: HttpRequest.Builder): Int = http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    /** Opens the events stream; the returned reader is closed with the server. */
    fun connectEvents(): SseTestReader {
        val body =
            http
                .send(
                    HttpRequest.newBuilder(URI("$base/api/events")).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                ).body()
        stream = body
        return SseTestReader(body.bufferedReader())
    }

    override fun close() {
        stream?.close()
        server.stop(gracePeriodMillis = 100, timeoutMillis = 5_000)
    }
}
