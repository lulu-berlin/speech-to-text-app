package stt.http

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import stt.config.Config
import java.nio.file.Files

class UnknownApiPathSpec : BehaviorSpec({

    val staticDir = tempdir()

    given("a built frontend in STT_STATIC_DIR") {
        `when`("requesting an unknown /api path") {
            then("404 is returned, not index.html") {
                Files.writeString(staticDir.toPath().resolve("index.html"), "<h1>index</h1>")
                testApplication {
                    application { sttApp(Config(staticDir = staticDir.toPath())) }
                    val response = client.get("/api/unknown")
                    response.status.shouldBe(HttpStatusCode.NotFound)
                }
            }
        }
    }
})