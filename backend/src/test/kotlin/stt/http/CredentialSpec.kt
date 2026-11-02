package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Roadmap item 66, test list item 9 (first slice): in app mode the service requires the per-install credential. A
 * request without it, or with another one, is refused with the contract's `401 unauthorized` body
 * (`docs/api/error.unauthorized.json`, ADR 0029); the TUI and the tray send it as an `Authorization` header and are
 * served. Dev/server mode (no credential configured) stays open, as every other route spec shows.
 */
class CredentialSpec :
    BehaviorSpec({

        given("the app serving with a per-install credential, as the service does") {
            `when`("a client asks for the task list without the credential, with another one, and with it") {
                then("the first two are refused with 401 and the one with the credential is served") {
                    verifyCleanStop {
                        application { sttApp(testConfig(), credential = "the-install-secret") }

                        val refused = client.get("/api/tasks") { ownHost() }
                        refused.status shouldBe HttpStatusCode.Unauthorized
                        Json.parseToJsonElement(refused.bodyAsText()).jsonObject shouldBe apiExample("error.unauthorized.json")
                        client
                            .get("/api/tasks") {
                                ownHost()
                                header(HttpHeaders.Authorization, "Bearer another-secret")
                            }.status shouldBe HttpStatusCode.Unauthorized
                        client
                            .get("/api/tasks") {
                                ownHost()
                                header(HttpHeaders.Authorization, "Bearer the-install-secret")
                            }.status shouldBe HttpStatusCode.OK
                    }
                }
            }
        }
    })
