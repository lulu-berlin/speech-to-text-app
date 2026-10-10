package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The contract's error rules: every error response, `500 internal` included, has the `{ "error", "message" }`
 * body and never leaks internals (probed 2026-10-10: before the app-level handler, an unhandled exception in a
 * route produced a 500 with an empty body). The throwing route here is test-only: it stands in for any
 * unexpected failure in any route.
 */
class InternalErrorSpec :
    BehaviorSpec({
        given("a route that throws") {
            `when`("it fails") {
                then("500 internal is returned with the error body, without the exception's text") {
                    testApplication {
                        application {
                            sttApp(stt.config.testConfig(tempdir("stt-data").toPath()))
                            routing {
                                put("/api/boom") {
                                    throw IllegalStateException("secrets-in-the-message")
                                }
                            }
                        }
                        val response =
                            client.put("/api/boom") {
                                contentType(ContentType.Application.Json)
                                setBody("{}")
                            }
                        response.status shouldBe HttpStatusCode.InternalServerError
                        response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("error.json")
                        body.getValue("error").jsonPrimitive.content shouldBe "internal"
                        // The exception text and the stack trace must not reach the client.
                        body
                            .getValue("message")
                            .jsonPrimitive.content
                            .shouldNotContain("secrets-in-the-message")
                        response.bodyAsText().shouldNotContain("at ")
                    }
                }
            }
        }
    })
