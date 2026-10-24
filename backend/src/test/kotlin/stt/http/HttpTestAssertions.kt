package stt.http

import io.kotest.matchers.shouldBe
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Asserts an error response (item 12's contract): [status], the JSON [code], and the `docs/api/error.json` shape. */
internal suspend fun HttpResponse.shouldBeError(
    status: HttpStatusCode,
    code: String,
) {
    this.status shouldBe status
    contentType()?.withoutParameters() shouldBe ContentType.Application.Json
    val body = Json.parseToJsonElement(bodyAsText()).jsonObject
    body shouldHaveShapeOf apiExample("error.json")
    body.getValue("error").jsonPrimitive.content shouldBe code
}
