package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode

class HealthSpec :
    BehaviorSpec({

        given("the application") {
            `when`("requesting GET /api/health") {
                then("""responds 200 with {"status":"ok"}""") {
                    verifyCleanStop {
                        application { sttApp(testConfig()) }
                        val response = client.get("/api/health")
                        response.status.shouldBe(HttpStatusCode.OK)
                        response.bodyAsText().shouldBe("""{"status":"ok"}""")
                    }
                }
            }
        }
    })
