package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import stt.transcribe.GpuDevice

/**
 * Roadmap item 32, cycle 2: `GET /api/settings` — the stored settings and the devices the startup probe
 * reported (CPU and auto always, then each GPU as `gpu:<index>`), shaped like its docs/api example.
 * The probe itself (with a real whisper-cli stand-in) is [DeviceProbeSpec].
 */
class SettingsRoutesSpec :
    BehaviorSpec({
        given("the app started on a fresh database, with the device probe having reported GPUs 0 and 1") {
            `when`("GET /api/settings") {
                then(
                    "it answers the stored defaults and the available devices cpu, auto, gpu:0, gpu:1 — the settings.json example — as JSON",
                ) {
                    val dataDir = tempdir("stt-settings").toPath()

                    testApplication {
                        application {
                            sttApp(
                                stt.config.testConfig(dataDir),
                                gpus = listOf(GpuDevice(0, "NVIDIA GeForce RTX 3060"), GpuDevice(1, "AMD Radeon RX 6600")),
                            )
                        }

                        val response = client.get("/api/settings")
                        response.status shouldBe HttpStatusCode.OK
                        response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("settings.json")
                        body shouldBe apiExample("settings.json")
                    }
                }
            }
        }

        given("the app started on a fresh database, with the device probe having reported no GPU") {
            `when`("GET /api/settings") {
                then("the answer lists cpu and auto, and no GPU") {
                    val dataDir = tempdir("stt-settings").toPath()

                    testApplication {
                        application { sttApp(stt.config.testConfig(dataDir)) }

                        val response = client.get("/api/settings")
                        response.status shouldBe HttpStatusCode.OK
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("settings.json")
                        body
                            .getValue("availableDevices")
                            .jsonArray
                            .map { it.jsonPrimitive.content }
                            .shouldBe(listOf("cpu", "auto"))
                        body
                            .getValue("gpuNames")
                            .jsonObject
                            .shouldBe(emptyMap<String, JsonElement>())
                    }
                }
            }
        }
    })
