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

class StaticFilesSpec :
    BehaviorSpec({

        val staticDir = tempdir()
        val dataDir = tempdir("stt-data").toPath()

        given("a static file existing under STT_STATIC_DIR") {
            `when`("requesting it by name") {
                then("it is returned with its content") {
                    Files.writeString(staticDir.toPath().resolve("hello.txt"), "hello")
                    testApplication {
                        application { sttApp(Config(dataDir = dataDir, staticDir = staticDir.toPath())) }
                        val response = client.get("/hello.txt")
                        response.status.shouldBe(HttpStatusCode.OK)
                        response.bodyAsText().shouldBe("hello")
                    }
                }
            }
        }
    })
