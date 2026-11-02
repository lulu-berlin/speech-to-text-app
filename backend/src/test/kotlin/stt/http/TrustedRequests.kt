package stt.http

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.net.CookieManager
import java.net.http.HttpClient

/**
 * The `Host` a browser on this machine sends to the service (roadmap item 66): app mode trusts loopback names only, and
 * the test clients, unlike a browser, send no `Host` of their own. The port is not checked, so any port stands in for
 * the service's.
 */
internal const val OWN_HOST = "127.0.0.1:8080"

/** Sends [OWN_HOST] (or [host]) as `Host`, as a browser on this machine does. */
internal fun HttpRequestBuilder.ownHost(host: String = OWN_HOST) {
    header(HttpHeaders.Host, host)
}

/**
 * A browser for the specs on the JDK client: it stores the cookies the service sets and sends them on every request,
 * `EventSource`'s included, and it does not follow a redirect, so a spec sees the handshake's own answer.
 */
internal fun browserHttpClient(): HttpClient =
    HttpClient
        .newBuilder()
        .cookieHandler(CookieManager())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
