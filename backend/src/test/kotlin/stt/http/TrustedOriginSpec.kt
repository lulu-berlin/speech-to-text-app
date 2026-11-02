package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Roadmap item 66, test list item 9 (the DNS-rebinding half of the trust boundary): in app mode the service serves only
 * requests that address it by a loopback name — a browser tricked by DNS rebinding sends its own host — and a request
 * whose `Origin` is another site is refused, so a page on the machine cannot drive it either. The bearer credential is
 * sent throughout, so only the origin decides. Refusals are the contract's `403 forbidden-origin`.
 */
class TrustedOriginSpec :
    BehaviorSpec({

        given("the app serving with a per-install credential, as the service does") {
            `when`("a request carries a foreign Host, a foreign Origin, and this machine's own address") {
                then("the first two are refused with 403 and the loopback request with its own Origin is served") {
                    verifyCleanStop {
                        application { sttApp(testConfig(), credential = "the-install-secret") }
                        val secret: HttpRequestBuilder.() -> Unit = {
                            header(HttpHeaders.Authorization, "Bearer the-install-secret")
                        }

                        val rebinding =
                            client.get("/api/tasks") {
                                secret()
                                header(HttpHeaders.Host, "attacker.example:8080")
                            }
                        rebinding.status shouldBe HttpStatusCode.Forbidden
                        Json.parseToJsonElement(rebinding.bodyAsText()).jsonObject shouldBe apiExample("error.forbidden.json")

                        client
                            .get("/api/tasks") {
                                secret()
                                header(HttpHeaders.Host, "127.0.0.1:8080")
                                header(HttpHeaders.Origin, "http://attacker.example")
                            }.status shouldBe HttpStatusCode.Forbidden

                        client
                            .get("/api/tasks") {
                                secret()
                                header(HttpHeaders.Host, "127.0.0.1:8080")
                                header(HttpHeaders.Origin, "http://127.0.0.1:8080")
                            }.status shouldBe HttpStatusCode.OK
                    }
                }
            }
        }
    })
