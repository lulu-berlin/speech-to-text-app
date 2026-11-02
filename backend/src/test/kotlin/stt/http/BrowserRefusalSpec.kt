package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Roadmap item 66, test-list D6: a browser that reaches app mode without the session cookie (an old bookmark, a cookie
 * the user cleared) is shown a page saying to open the app from the tray or `stt-app open`, not the bare `401`
 * JSON. API requests keep the JSON contract whatever they accept, so the frontend and the other clients see no change.
 */
class BrowserRefusalSpec :
    BehaviorSpec({

        given("the app serving with a per-install credential") {
            `when`("a page navigation arrives without the cookie") {
                then("it answers an HTML page that names the tray and `stt-app open`") {
                    verifyCleanStop {
                        application { sttApp(testConfig(), credential = "the-install-secret") }

                        val page =
                            client.get("/") {
                                ownHost()
                                acceptHtml()
                            }
                        page.status shouldBe HttpStatusCode.Unauthorized
                        page.contentType() shouldStartWith "text/html"
                        val html = page.bodyAsText()
                        html.shouldContainIgnoringCase("tray")
                        html.shouldContain("stt-app open")

                        val api =
                            client.get("/api/tasks") {
                                ownHost()
                                acceptHtml()
                            }
                        api.status shouldBe HttpStatusCode.Unauthorized
                        api.contentType() shouldStartWith "application/json"
                        Json.parseToJsonElement(api.bodyAsText()).jsonObject shouldBe apiExample("error.unauthorized.json")

                        val notABrowser = client.get("/") { ownHost() }
                        notABrowser.status shouldBe HttpStatusCode.Unauthorized
                        notABrowser.contentType() shouldStartWith "application/json"
                        Json.parseToJsonElement(notABrowser.bodyAsText()).jsonObject shouldBe apiExample("error.unauthorized.json")
                    }
                }
            }
        }
    })

private fun io.ktor.client.request.HttpRequestBuilder.acceptHtml() =
    header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

private fun HttpResponse.contentType(): String = headers[HttpHeaders.ContentType].orEmpty()
