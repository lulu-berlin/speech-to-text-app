package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Roadmap item 66, test list item 9 (the browser's credential): `stt-app open` and the tray get a single-use launch
 * token from the service with the per-install credential, and open `GET /open?t=<token>` in the OS browser. The
 * service answers with an `HttpOnly`, `SameSite=Strict` session cookie and a redirect to the app, and the cookie then
 * authorizes the app's own requests — the secret itself never reaches the browser. A second use of the token, or a
 * browser without the cookie, is refused.
 */
class LaunchTokenSpec :
    BehaviorSpec({

        given("the app serving with a per-install credential, as the service does") {
            `when`("a browser opens the launch URL once and the app then asks for its data") {
                then(
                    "the launch sets an HttpOnly SameSite=Strict cookie and redirects, the cookie is served, and the token cannot be used twice",
                ) {
                    verifyCleanStop {
                        application { sttApp(testConfig(), credential = "the-install-secret") }
                        val launched =
                            client.post("/api/launch-token") {
                                ownHost()
                                header(HttpHeaders.Authorization, "Bearer the-install-secret")
                            }
                        launched.status shouldBe HttpStatusCode.OK
                        val body = Json.parseToJsonElement(launched.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("launch-token.json")
                        val token = body.getValue("token").jsonPrimitive.content

                        // A browser: it stores what the launch sets and sends it back, and it does not follow the jump.
                        val browser =
                            createClient {
                                followRedirects = false
                                install(HttpCookies)
                            }
                        val opened = browser.get("/open?t=$token") { ownHost() }
                        opened.status.value shouldBe 303
                        opened.headers[HttpHeaders.Location] shouldBe "/"
                        val setCookie = opened.headers[HttpHeaders.SetCookie].orEmpty()
                        setCookie shouldContainIgnoringCase "HttpOnly"
                        setCookie shouldContainIgnoringCase "SameSite=Strict"

                        browser.get("/api/tasks") { ownHost() }.status shouldBe HttpStatusCode.OK
                        browser.get("/open?t=$token") { ownHost() }.status shouldBe HttpStatusCode.Unauthorized
                        createClient {}.get("/api/tasks") { ownHost() }.status shouldBe HttpStatusCode.Unauthorized
                    }
                }
            }
        }
    })
