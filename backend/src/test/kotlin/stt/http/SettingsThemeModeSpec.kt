package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Roadmap item 67 (the theme amendment): the theme mode is one user preference — auto, light or dark — shared by every
 * frontend, so the service stores it and `GET`/`PUT /api/settings` and `settings-updated` carry it. Auto is the
 * default; any other value is refused like every other invalid setting.
 */
class SettingsThemeModeSpec :
    BehaviorSpec({
        val settingsExamples = listOf("settings.json", "settings.fixed.json", "settings.fallback.json").map { apiExample(it) }
        val eventExamples =
            listOf("settings-updated.json", "settings-updated.defaults.json", "settings-updated.fallback.json").map { apiExample(it) }

        given("the app on a fresh database and an open events stream") {
            `when`("the settings are read, the theme mode is set to light, and later to sepia") {
                then("it defaults to auto, light is stored and published, and sepia is 400 invalid-theme") {
                    SseServer({ sttApp(stt.config.testConfig(tempdir("stt-theme-mode").toPath())) }).use { server ->
                        val events = server.connectEvents()
                        events.readEvent().first shouldBe "snapshot"

                        val initial = Json.parseToJsonElement(server.request("GET", "/api/settings").second).jsonObject
                        initial shouldHaveShapeOf settingsExamples
                        themeOf(initial) shouldBe "auto"

                        val (status, body) = server.request("PUT", "/api/settings", settingsBody(theme = "\"light\""))
                        status shouldBe 200
                        themeOf(Json.parseToJsonElement(body).jsonObject) shouldBe "light"

                        themeOf(Json.parseToJsonElement(server.request("GET", "/api/settings").second).jsonObject) shouldBe
                            "light"

                        val (name, data) = events.readEvent()
                        name shouldBe "settings-updated"
                        val event = Json.parseToJsonElement(data).jsonObject
                        event shouldHaveShapeOf eventExamples
                        themeOf(event) shouldBe "light"

                        val (rejected, answer) = server.request("PUT", "/api/settings", settingsBody(theme = "\"sepia\""))
                        rejected shouldBe 400
                        Json
                            .parseToJsonElement(answer)
                            .jsonObject
                            .getValue("error")
                            .jsonPrimitive
                            .content shouldBe "invalid-theme"

                        themeOf(Json.parseToJsonElement(server.request("GET", "/api/settings").second).jsonObject) shouldBe
                            "light"
                    }
                }
            }
        }
    })

/** The theme mode a settings JSON object (a read, an answer or an event) carries. */
private fun themeOf(settings: JsonObject): String = settings.getValue("themeMode").jsonPrimitive.content

/** A `PUT /api/settings` body: the fresh-database defaults, with the theme mode given as raw JSON. */
private fun settingsBody(theme: String): String =
    """{ "device": "auto", "threads": null, "defaultLanguage": "auto", "speakersMode": "auto", """ +
        """"speakersCount": null, "maxSpeakers": 8, "themeMode": $theme }"""
