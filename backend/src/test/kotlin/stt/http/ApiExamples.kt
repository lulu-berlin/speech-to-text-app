package stt.http

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import stt.docsApiFile
import java.nio.file.Files

/** A contract example from `docs/api/` (item 12's contract). */
internal fun apiExample(fileName: String): JsonObject = Json.parseToJsonElement(Files.readString(docsApiFile(fileName))).jsonObject

/** Asserts the example's structure (docs/api/README.md): the same keys, each with the same JSON value type. */
internal infix fun JsonObject.shouldHaveShapeOf(example: JsonObject) {
    keys shouldContainExactlyInAnyOrder example.keys
    for ((key, value) in example) {
        withClue(key) { jsonType(getValue(key)) shouldBe jsonType(value) }
    }
}

private fun jsonType(element: JsonElement): String =
    when {
        element is JsonNull -> "null"
        element is JsonObject -> "object"
        element is JsonArray -> "array"
        (element as JsonPrimitive).isString -> "string"
        element.content == "true" || element.content == "false" -> "boolean"
        else -> "number"
    }
