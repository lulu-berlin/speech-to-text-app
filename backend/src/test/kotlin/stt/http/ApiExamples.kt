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
import java.nio.file.Files
import java.nio.file.Path

/** A contract example from `docs/api/` (item 12's contract), resolved from the Gradle project directory (`backend/`). */
internal fun apiExample(fileName: String): JsonObject {
    val candidates = listOf(Path.of("../docs/api", fileName), Path.of("docs/api", fileName))
    val file =
        candidates.firstOrNull(Files::exists)
            ?: error("$fileName not found; working directory is ${Path.of("").toAbsolutePath()}")
    return Json.parseToJsonElement(Files.readString(file)).jsonObject
}

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
