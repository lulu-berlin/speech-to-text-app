package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Roadmap item 88, cycle 1: the App language setting — a fresh database has none, so the app follows the system's
 * language, and a stored choice survives a restart.
 */
class SettingsAppLanguageSpec :
    BehaviorSpec({
        given("the app started on a fresh data directory") {
            `when`("the settings are read, the App language is set to German, and the app is restarted") {
                then("a fresh database has no App language and the stored choice survives the restart") {
                    val dataDir = tempdir("stt-settings-app-language").toPath()

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val fresh = client.get("/api/settings")
                        fresh.status shouldBe HttpStatusCode.OK
                        Json
                            .parseToJsonElement(fresh.bodyAsText())
                            .jsonObject
                            .getValue("appLanguage") shouldBe JsonNull

                        val written =
                            client.put("/api/settings") {
                                contentType(ContentType.Application.Json)
                                setBody(
                                    """
                                    {
                                      "device": "auto",
                                      "threads": null,
                                      "defaultLanguage": "auto",
                                      "speakersMode": "auto",
                                      "speakersCount": null,
                                      "maxSpeakers": 8,
                                      "appLanguage": "de"
                                    }
                                    """.trimIndent(),
                                )
                            }
                        written.status shouldBe HttpStatusCode.OK
                        Json
                            .parseToJsonElement(written.bodyAsText())
                            .jsonObject
                            .getValue("appLanguage")
                            .jsonPrimitive
                            .content shouldBe "de"
                    }

                    // A restart: the same data directory is opened by a new application.
                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val restarted = client.get("/api/settings")
                        restarted.status shouldBe HttpStatusCode.OK
                        Json
                            .parseToJsonElement(restarted.bodyAsText())
                            .jsonObject
                            .getValue("appLanguage")
                            .jsonPrimitive
                            .content shouldBe "de"
                    }
                }
            }
        }
    })
