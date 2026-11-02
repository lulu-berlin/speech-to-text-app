package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject

/**
 * Roadmap item 66, test-list D10 (the setting): the terminal command is one user preference — the command line of the
 * terminal the TUI opens in, replacing the OS launcher when it is set — so it lives in the settings row and travels on
 * `GET`/`PUT /api/settings`. `null` means "use the OS launcher".
 */
class SettingsTerminalCommandSpec :
    BehaviorSpec({
        given("the app on a fresh database") {
            `when`("PUT /api/settings sets a terminal command") {
                then("GET answers it, and a later PUT can clear it again") {
                    SseServer({ sttApp(stt.config.testConfig(tempdir("stt-terminal-command").toPath())) }).use { server ->
                        val (status, body) =
                            server.request("PUT", "/api/settings", settingsWith("\"wezterm start --\""))
                        status shouldBe 200
                        terminalOf(body) shouldBe Json.parseToJsonElement("\"wezterm start --\"")

                        val (_, read) = server.request("GET", "/api/settings")
                        terminalOf(read) shouldBe Json.parseToJsonElement("\"wezterm start --\"")

                        val (clearedStatus, cleared) = server.request("PUT", "/api/settings", settingsWith("null"))
                        clearedStatus shouldBe 200
                        terminalOf(cleared) shouldBe JsonNull
                    }
                }
            }
        }
    })

private fun terminalOf(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("terminalCommand")

/** The settings body with every required field, and `terminalCommand` set to [command] as raw JSON. */
private fun settingsWith(command: String): String =
    """{"device":"auto","threads":null,"defaultLanguage":"auto","speakersMode":"auto","speakersCount":null,""" +
        """"maxSpeakers":8,"defaultFrontend":null,"terminalCommand":$command}"""
