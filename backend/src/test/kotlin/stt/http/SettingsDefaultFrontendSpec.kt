package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Roadmap item 66 (test-list item 3, the default frontend): the default frontend is one user preference the browser,
 * the window, the TUI and the tray share, so it lives in the settings row and travels on `GET`/`PUT /api/settings`.
 * `null` means "no default chosen yet".
 */
class SettingsDefaultFrontendSpec :
    BehaviorSpec({
        given("the app on a fresh database") {
            `when`("PUT /api/settings names the browser as the default frontend") {
                then("GET answers it, and a later PUT can clear it again") {
                    SseServer({ sttApp(stt.config.testConfig(tempdir("stt-default-frontend").toPath())) }).use { server ->
                        val (status, body) = server.request("PUT", "/api/settings", settingsWith("\"browser\""))
                        status shouldBe 200
                        frontendOf(body) shouldBe "browser"

                        val (_, read) = server.request("GET", "/api/settings")
                        frontendOf(read) shouldBe "browser"

                        val (clearedStatus, cleared) = server.request("PUT", "/api/settings", settingsWith("null"))
                        clearedStatus shouldBe 200
                        Json
                            .parseToJsonElement(cleared)
                            .jsonObject
                            .getValue("defaultFrontend")
                            .jsonPrimitive
                            .content shouldBe "null"
                    }
                }
            }
        }
    })

private fun frontendOf(body: String): String =
    Json
        .parseToJsonElement(body)
        .jsonObject
        .getValue("defaultFrontend")
        .jsonPrimitive.content

/** The settings body with every required field, and `defaultFrontend` set to [frontend] as raw JSON. */
private fun settingsWith(frontend: String): String =
    """{"device":"auto","threads":null,"defaultLanguage":"auto","speakersMode":"auto","speakersCount":null,""" +
        """"maxSpeakers":8,"defaultFrontend":$frontend}"""
