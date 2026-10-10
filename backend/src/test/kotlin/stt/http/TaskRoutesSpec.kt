package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TaskRoutesSpec :
    BehaviorSpec({
        given("an empty task history") {
            `when`("requesting an unknown task") {
                then("404 is returned with a not-found error body") {
                    testApplication {
                        application { sttApp(testConfig()) }
                        val response = client.get("/api/tasks/999")
                        response.status shouldBe HttpStatusCode.NotFound
                        response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        body shouldHaveShapeOf apiExample("error.json")
                        body.getValue("error").jsonPrimitive.content shouldBe "not-found"
                        body.getValue("message").jsonPrimitive.content shouldContain "999"
                    }
                }
            }
        }
    })
