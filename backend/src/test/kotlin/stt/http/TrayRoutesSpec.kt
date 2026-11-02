package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

class TrayRoutesSpec :
    BehaviorSpec({
        given("the app serving with a per-install credential") {
            `when`("the tray registers itself") {
                then("the credential is required and the registration reaches the supervisor") {
                    verifyCleanStop {
                        val registered = mutableListOf<Pair<Long, List<String>>>()
                        application {
                            sttApp(
                                testConfig(),
                                credential = "the-install-secret",
                                registerTray = { pid, command -> registered += pid to command },
                            )
                        }
                        val body = """{"pid":4242,"command":["/opt/stt/bin/stt-tray"]}"""
                        val withoutCredential =
                            client.post("/api/tray") {
                                ownHost()
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                        withoutCredential.status shouldBe HttpStatusCode.Unauthorized
                        registered shouldBe emptyList()

                        val answered =
                            client.post("/api/tray") {
                                ownHost()
                                header(HttpHeaders.Authorization, "Bearer the-install-secret")
                                contentType(ContentType.Application.Json)
                                setBody(body)
                            }
                        answered.status shouldBe HttpStatusCode.Accepted
                        registered shouldBe listOf(4242L to listOf("/opt/stt/bin/stt-tray"))
                    }
                }
            }
        }
    })
