package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Roadmap item 66: the live event stream is part of the browser's session. A browser that entered through the launch
 * token's `HttpOnly` cookie keeps that cookie on `EventSource`'s own request — the frontend cannot add a header there —
 * so the stream must answer it with the snapshot, and refuse a client that never launched.
 */
class EventsCookieSpec :
    BehaviorSpec({

        given("the app serving with a per-install credential, as the service does") {
            `when`("a browser opens the launch URL and then the event stream, and a client without the cookie asks for it") {
                then("the browser's stream opens with the snapshot and the client without the cookie is refused") {
                    SseServer({ sttApp(testConfig(), credential = "the-install-secret") }, browserHttpClient()).use { server ->
                        val ownHost = mapOf("Host" to OWN_HOST)
                        val (launched, body) =
                            server.request(
                                "POST",
                                "/api/launch-token",
                                headers = ownHost + mapOf("Authorization" to "Bearer the-install-secret"),
                            )
                        launched shouldBe 200
                        val token =
                            Json
                                .parseToJsonElement(body)
                                .jsonObject
                                .getValue("token")
                                .toString()
                                .trim('"')

                        server.request("GET", "/open?t=$token", headers = ownHost).first shouldBe 303

                        server.connectEvents(ownHost).use { events ->
                            val (name, data) = events.readEvent()
                            name shouldBe "snapshot"
                            Json.parseToJsonElement(data).jsonObject shouldHaveShapeOf apiExample("snapshot.json")
                        }
                    }

                    SseServer({ sttApp(testConfig(), credential = "the-install-secret") }).use { server ->
                        server
                            .request("GET", "/api/events", headers = mapOf("Host" to OWN_HOST))
                            .first shouldBe 401
                    }
                }
            }
        }
    })
