package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import stt.config.Config
import java.nio.file.Files

class SpaFallbackSpec :
    BehaviorSpec({

        val staticDir = tempdir()
        val dataDir = tempdir("stt-data").toPath()

        given("a built frontend with index.html in STT_STATIC_DIR") {
            `when`("requesting an unknown non-/api path") {
                then("index.html is returned") {
                    val indexPath = staticDir.toPath().resolve("index.html")
                    Files.writeString(indexPath, "<!DOCTYPE html><html><head><title>stt</title></head><body>hi</body></html>")
                    testApplication {
                        application { sttApp(Config(dataDir = dataDir, staticDir = staticDir.toPath())) }
                        val response = client.get("/some/route")
                        response.status.shouldBe(HttpStatusCode.OK)
                        response.bodyAsText().shouldBe(Files.readString(indexPath))
                    }
                }
            }
        }

        given("a path merely starting with /api (not under it)") {
            `when`("requesting it with static serving enabled") {
                then("it gets the SPA fallback, not 404") {
                    Files.writeString(staticDir.toPath().resolve("index.html"), "<h1>index</h1>")
                    testApplication {
                        application { sttApp(Config(dataDir = dataDir, staticDir = staticDir.toPath())) }
                        val response = client.get("/apiary")
                        response.status.shouldBe(HttpStatusCode.OK)
                        response.bodyAsText().shouldBe("<h1>index</h1>")
                    }
                }
            }
        }
    })
