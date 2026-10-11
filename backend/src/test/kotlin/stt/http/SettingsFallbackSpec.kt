package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import stt.config.Device
import stt.db.Database
import stt.db.SettingsRepository

/**
 * Roadmap item 32, cycle 6 (backend half): the CPU-fallback notice is stored with the settings — after the engine
 * switched an unavailable GPU to the CPU, `GET /api/settings` names the GPU in `deviceFallback`, also after a restart,
 * until `DELETE /api/settings/device-fallback` dismisses it or a `PUT` chooses a device again; both publish it.
 */
class SettingsFallbackSpec :
    BehaviorSpec({
        val settingsExamples = listOf("settings.json", "settings.fixed.json", "settings.fallback.json").map { apiExample(it) }
        val eventExamples =
            listOf("settings-updated.json", "settings-updated.defaults.json", "settings-updated.fallback.json").map { apiExample(it) }

        given("a data directory whose GPU 1 was switched to the CPU by an earlier run, and a restarted app") {
            `when`("reading the settings, dismissing the notice, and reading them again") {
                then("the notice names gpu:1 with the device cpu until it is dismissed; the dismiss answers and publishes it as null") {
                    val dataDir = tempdir("stt-settings").toPath()
                    Database.open(dataDir).use { db ->
                        val settings = SettingsRepository(db)
                        settings.update(settings.get().copy(device = Device.Gpu(1)))
                        settings.fallBackToCpu(Device.Gpu(1))
                    }

                    SseServer({ sttApp(stt.config.testConfig(dataDir)) }).use { server ->
                        val events = server.connectEvents()
                        events.readEvent().first shouldBe "snapshot"

                        val before = Json.parseToJsonElement(server.request("GET", "/api/settings").second).jsonObject
                        before shouldHaveShapeOf settingsExamples
                        (before.getValue("device") to before.getValue("deviceFallback")) shouldBe
                            (JsonPrimitive("cpu") to JsonPrimitive("gpu:1"))

                        val (status, body) = server.request("DELETE", "/api/settings/device-fallback")
                        status shouldBe 200
                        val dismissed = Json.parseToJsonElement(body).jsonObject
                        dismissed shouldHaveShapeOf settingsExamples
                        (dismissed.getValue("device") to dismissed.getValue("deviceFallback")) shouldBe (JsonPrimitive("cpu") to JsonNull)

                        val (name, data) = events.readEvent()
                        name shouldBe "settings-updated"
                        val event = Json.parseToJsonElement(data).jsonObject
                        event shouldHaveShapeOf eventExamples
                        event.getValue("deviceFallback") shouldBe JsonNull

                        Json
                            .parseToJsonElement(server.request("GET", "/api/settings").second)
                            .jsonObject
                            .getValue("deviceFallback") shouldBe JsonNull
                    }
                }
            }
        }

        given("a stored notice naming GPU 1") {
            `when`("a PUT chooses a device again") {
                then("the notice is cleared in the answer and in what is stored") {
                    val dataDir = tempdir("stt-settings").toPath()
                    Database.open(dataDir).use { db -> SettingsRepository(db).fallBackToCpu(Device.Gpu(1)) }

                    SseServer({ sttApp(stt.config.testConfig(dataDir)) }).use { server ->
                        val body =
                            """{ "device": "gpu:1", "threads": null, "defaultLanguage": "auto", "speakersMode": "auto", "speakersCount": null, "maxSpeakers": 8 }"""
                        val (status, answer) = server.request("PUT", "/api/settings", body)
                        status shouldBe 200
                        Json.parseToJsonElement(answer).jsonObject.getValue("deviceFallback") shouldBe JsonNull
                    }
                    Database.open(dataDir).use { db ->
                        val stored = SettingsRepository(db).get()
                        (stored.device to stored.deviceFallback) shouldBe (Device.Gpu(1) to null)
                    }
                }
            }
        }
    })
