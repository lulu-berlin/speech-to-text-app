package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import stt.config.Device
import stt.db.DEFAULT_SETTINGS
import stt.db.Database
import stt.db.SettingsRepository
import stt.db.SpeakersMode
import stt.transcribe.GpuDevice

/**
 * Roadmap item 72 (ADR 0062): `DELETE /api/settings` restores the defaults in one write — `auto`, `null`, `auto`,
 * `auto`, `null`, `8`, the values `V6` seeds — clears the CPU-fallback notice in the same write, answers `200`
 * Settings and publishes `settings-updated`, so other tabs follow. The concrete values are pinned here as well as
 * compared to the examples, so a reset that drifts from the seed cannot pass.
 */
class SettingsResetSpec :
    BehaviorSpec({
        val settingsExamples = listOf("settings.json", "settings.fixed.json", "settings.fallback.json").map { apiExample(it) }
        val eventExamples =
            listOf("settings-updated.json", "settings-updated.defaults.json", "settings-updated.fallback.json").map { apiExample(it) }

        given("stored settings that differ from the defaults, with a CPU-fallback notice") {
            `when`("DELETE /api/settings") {
                then("it answers the defaults, publishes them as settings-updated, and stores them") {
                    val dataDir = tempdir("stt-settings").toPath()
                    Database.open(dataDir).use { db ->
                        val settings = SettingsRepository(db)
                        // The migration's seed and the reset's constant are the same defaults:
                        // a V6 change that leaves the constant behind fails here.
                        settings.get() shouldBe DEFAULT_SETTINGS
                        settings.update(
                            settings.get().copy(
                                device = Device.Gpu(1),
                                threads = 6,
                                defaultLanguage = "de",
                                speakersMode = SpeakersMode.FIXED,
                                speakersCount = 3,
                                defaultMaxSpeakers = 12,
                            ),
                        )
                        settings.fallBackToCpu(Device.Gpu(1))
                    }

                    val gpus = listOf(GpuDevice(0, "NVIDIA GeForce RTX 3060"), GpuDevice(1, "AMD Radeon RX 6600"))
                    SseServer({ sttApp(stt.config.testConfig(dataDir), gpus = gpus) }).use { server ->
                        val events = server.connectEvents()
                        events.readEvent().first shouldBe "snapshot"

                        val (status, body) = server.request("DELETE", "/api/settings")
                        status shouldBe 200
                        val reset = Json.parseToJsonElement(body).jsonObject
                        reset shouldHaveShapeOf settingsExamples
                        reset shouldBe apiExample("settings.json")

                        // Pinned: auto, null, auto, auto, null, 8 (V6's seed), notice cleared.
                        (reset.getValue("device") to reset.getValue("threads")) shouldBe
                            (JsonPrimitive("auto") to JsonNull)
                        reset.getValue("defaultLanguage") shouldBe JsonPrimitive("auto")
                        (reset.getValue("speakersMode") to reset.getValue("speakersCount")) shouldBe
                            (JsonPrimitive("auto") to JsonNull)
                        reset.getValue("maxSpeakers") shouldBe JsonPrimitive(8)
                        reset.getValue("deviceFallback") shouldBe JsonNull

                        val (name, data) = events.readEvent()
                        name shouldBe "settings-updated"
                        val event = Json.parseToJsonElement(data).jsonObject
                        event shouldHaveShapeOf eventExamples
                        event shouldBe apiExample("settings-updated.defaults.json")

                        // The reset is idempotent: a second call answers the same defaults.
                        val (againStatus, againBody) = server.request("DELETE", "/api/settings")
                        againStatus shouldBe 200
                        Json.parseToJsonElement(againBody).jsonObject shouldBe reset
                    }

                    Database.open(dataDir).use { db ->
                        val stored = SettingsRepository(db).get()
                        (stored.device to stored.threads) shouldBe (Device.Auto to null)
                        (stored.defaultLanguage to stored.speakersMode) shouldBe ("auto" to SpeakersMode.AUTO)
                        (stored.speakersCount to stored.defaultMaxSpeakers) shouldBe (null to 8)
                        stored.deviceFallback shouldBe null
                    }
                }
            }
        }
    })
