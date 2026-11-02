package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/**
 * Roadmap item 66 (test-list item 3, uninstalling a frontend): a default frontend that is no longer installed is
 * not offered again. The settings keep the chosen id, so the service drops it once the registry no longer has it —
 * the answer says none, and the stored value is cleared with it.
 */
class StaleDefaultFrontendSpec :
    BehaviorSpec({
        given("a registry holding only the browser, while the settings name an uninstalled tui") {
            `when`("GET /api/settings") {
                then("it answers no default frontend and clears the stored one") {
                    val registry = tempdir("stt-stale-frontend").toPath().resolve("frontends")
                    Files.createDirectories(registry)
                    Files.writeString(
                        registry.resolve("browser.json"),
                        """{"version":1,"id":"browser","kind":"browser","name":"Browser","url":"/"}""",
                    )
                    SseServer(
                        {
                            sttApp(
                                stt.config.testConfig(tempdir("stt-stale-data").toPath()),
                                frontendsDir = registry,
                            )
                        },
                    ).use { server ->
                        val (written, body) = server.request("PUT", "/api/settings", settingsNaming("tui"))
                        written shouldBe 200
                        frontendOf(body) shouldBe "tui"

                        val (_, read) = server.request("GET", "/api/settings")
                        frontendOf(read) shouldBe "null"

                        val (_, again) = server.request("GET", "/api/settings")
                        frontendOf(again) shouldBe "null"
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
private fun settingsNaming(frontend: String): String =
    """{"device":"auto","threads":null,"defaultLanguage":"auto","speakersMode":"auto","speakersCount":null,""" +
        """"maxSpeakers":8,"defaultFrontend":"$frontend"}"""
