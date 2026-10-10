package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import stt.config.Config
import stt.config.TEST_SPEAKER_MODEL
import stt.config.TEST_WHISPER_MODEL

class StaticDisabledSpec :
    BehaviorSpec({

        val dataDir = tempdir("stt-data")

        given("an application configured without STT_STATIC_DIR") {
            `when`("requesting /api/health and an unknown non-/api path") {
                then("health responds 200 and the path returns 404") {
                    verifyCleanStop {
                        application {
                            sttApp(
                                Config.fromEnv(
                                    mapOf(
                                        "STT_DATA_DIR" to dataDir.path,
                                        "STT_SPEAKER_MODEL" to TEST_SPEAKER_MODEL,
                                        "STT_WHISPER_MODEL" to TEST_WHISPER_MODEL,
                                    ),
                                ),
                            )
                        }
                        client.get("/api/health").status.shouldBe(HttpStatusCode.OK)
                        client.get("/some/route").status.shouldBe(HttpStatusCode.NotFound)
                    }
                }
            }
        }
    })
