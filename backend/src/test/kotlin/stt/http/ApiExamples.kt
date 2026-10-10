package stt.http

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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

/**
 * Asserts the structure of a shape given by its example files (docs/api/README.md): the same keys, each with a
 * JSON value type one of the examples has for it, so `null` only where some example has `null`.
 */
internal infix fun JsonObject.shouldHaveShapeOf(examples: List<JsonObject>) {
    keys shouldContainExactlyInAnyOrder examples.first().keys
    for (key in keys) {
        withClue(key) { examples.map { jsonType(it.getValue(key)) }.toSet() shouldContain jsonType(getValue(key)) }
    }
}

internal infix fun JsonObject.shouldHaveShapeOf(example: JsonObject) = shouldHaveShapeOf(listOf(example))

/** Every example of the Task shape. */
internal val taskExamples: List<JsonObject> =
    listOf("queued", "running", "diarizing", "paused", "completed", "failed", "canceled").map { apiExample("task.$it.json") }

private fun jsonType(element: JsonElement): String =
    when {
        element is JsonNull -> "null"
        element is JsonObject -> "object"
        element is JsonArray -> "array"
        (element as JsonPrimitive).isString -> "string"
        element.content == "true" || element.content == "false" -> "boolean"
        else -> "number"
    }
