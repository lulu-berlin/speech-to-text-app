package stt.http

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.transcribe.GpuDevice

/**
 * Roadmap item 32, cycle 3: `PUT /api/settings` validates the settings as a whole and stores them; the `200`
 * and a `settings-updated` event carry the new settings; a rejection (400 with its code) stores and emits nothing.
 */
class SettingsUpdateSpec :
    BehaviorSpec({
        val gpus = listOf(GpuDevice(0, "NVIDIA GeForce RTX 3060"), GpuDevice(1, "AMD Radeon RX 6600"))
        val settingsExamples = listOf(apiExample("settings.json"), apiExample("settings.fixed.json"))
        val eventExamples = listOf(apiExample("settings-updated.json"), apiExample("settings-updated.defaults.json"))
        val errorExamples =
            listOf(apiExample("error.json"), apiExample("error.invalid-language.json"), apiExample("error.invalid-device.json"))

        given("the app on a fresh database, the probe having reported GPUs 0 and 1, and an open events stream") {
            `when`("PUT /api/settings with GPU 1, 6 threads, German, 3 fixed speakers and 12 max speakers") {
                then("the 200, a later GET and the settings-updated event carry the new settings") {
                    SseServer({ sttApp(stt.config.testConfig(tempdir("stt-settings").toPath()), gpus) }).use { server ->
                        val events = server.connectEvents()
                        events.readEvent().first shouldBe "snapshot"

                        val (status, body) =
                            server.request(
                                "PUT",
                                "/api/settings",
                                settingsBody(
                                    device = "\"gpu:1\"",
                                    threads = "6",
                                    language = "\"de\"",
                                    mode = "\"fixed\"",
                                    count = "3",
                                    max = "12",
                                ),
                            )
                        status shouldBe 200
                        val answered = Json.parseToJsonElement(body).jsonObject
                        answered shouldHaveShapeOf settingsExamples
                        // By hand: the written fields, plus the choices cpu, auto and the two reported GPUs, named as `gpus` reports them.
                        val expected =
                            Json.parseToJsonElement(
                                """{ "device": "gpu:1", "threads": 6, "defaultLanguage": "de", "speakersMode": "fixed", "speakersCount": 3, "maxSpeakers": 12, "deviceFallback": null, "availableDevices": ["cpu", "auto", "gpu:0", "gpu:1"], "gpuNames": { "gpu:0": "NVIDIA GeForce RTX 3060", "gpu:1": "AMD Radeon RX 6600" } }""",
                            )
                        answered shouldBe expected

                        val (name, data) = events.readEvent()
                        name shouldBe "settings-updated"
                        val event = Json.parseToJsonElement(data).jsonObject
                        event shouldHaveShapeOf eventExamples
                        event shouldBe
                            Json.parseToJsonElement(
                                """{ "device": "gpu:1", "threads": 6, "defaultLanguage": "de", "speakersMode": "fixed", "speakersCount": 3, "maxSpeakers": 12, "deviceFallback": null }""",
                            )

                        val (getStatus, getBody) = server.request("GET", "/api/settings")
                        getStatus shouldBe 200
                        Json.parseToJsonElement(getBody) shouldBe expected
                    }
                }
            }
        }

        given("the app on a fresh database and an open events stream") {
            `when`("PUT /api/settings with one invalid field at a time, then one valid write") {
                then("each is 400 with its code and changes nothing; the first event after the snapshot is the valid write's") {
                    SseServer({ sttApp(stt.config.testConfig(tempdir("stt-settings").toPath())) }).use { server ->
                        val events = server.connectEvents()
                        events.readEvent().first shouldBe "snapshot"
                        val defaults = Json.parseToJsonElement(server.request("GET", "/api/settings").second)

                        val rejections =
                            listOf(
                                settingsBody(device = "\"tpu\"") to "invalid-device",
                                settingsBody(device = "\"gpu:-1\"") to "invalid-device",
                                settingsBody(device = "\"gpu:x\"") to "invalid-device",
                                settingsBody(device = "\"gpu\"") to "invalid-device",
                                settingsBody(threads = "0") to "invalid-threads",
                                settingsBody(language = "\"xx\"") to "invalid-language",
                                settingsBody(mode = "\"many\"") to "invalid-speakers",
                                settingsBody(mode = "\"fixed\"", count = "null") to "invalid-speakers",
                                settingsBody(mode = "\"fixed\"", count = "21") to "invalid-speakers",
                                settingsBody(mode = "\"auto\"", count = "2") to "invalid-speakers",
                                settingsBody(max = "0") to "invalid-max-speakers",
                                settingsBody(max = "21") to "invalid-max-speakers",
                            )
                        for ((body, code) in rejections) {
                            withClue(body) {
                                val (status, answer) = server.request("PUT", "/api/settings", body)
                                status shouldBe 400
                                val error = Json.parseToJsonElement(answer).jsonObject
                                error shouldHaveShapeOf errorExamples
                                error.getValue("error").jsonPrimitive.content shouldBe code
                            }
                        }
                        Json.parseToJsonElement(server.request("GET", "/api/settings").second) shouldBe defaults

                        server.command("PUT", "/api/settings", settingsBody(device = "\"cpu\"")) shouldBe 200
                        val (name, data) = events.readEvent()
                        name shouldBe "settings-updated"
                        Json
                            .parseToJsonElement(data)
                            .jsonObject
                            .getValue("device")
                            .jsonPrimitive.content shouldBe "cpu"
                    }
                }
            }
        }
    })

/** A `PUT /api/settings` body: the fresh-database defaults, with any field replaced by the given JSON literal. */
private fun settingsBody(
    device: String = "\"auto\"",
    threads: String = "null",
    language: String = "\"auto\"",
    mode: String = "\"auto\"",
    count: String = "null",
    max: String = "8",
): String =
    """{ "device": $device, "threads": $threads, "defaultLanguage": $language, "speakersMode": $mode, "speakersCount": $count, "maxSpeakers": $max }"""
