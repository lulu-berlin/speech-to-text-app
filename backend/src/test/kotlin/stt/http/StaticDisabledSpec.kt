package stt.http

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import stt.config.Config

class StaticDisabledSpec : BehaviorSpec({

    given("an application configured without STT_STATIC_DIR") {
        `when`("requesting /api/health and an unknown non-/api path") {
            then("health responds 200 and the path returns 404") {
                testApplication {
                    application { sttApp(Config.fromEnv(mapOf())) }
                    client.get("/api/health").status.shouldBe(HttpStatusCode.OK)
                    client.get("/some/route").status.shouldBe(HttpStatusCode.NotFound)
                }
            }
        }
    }
})