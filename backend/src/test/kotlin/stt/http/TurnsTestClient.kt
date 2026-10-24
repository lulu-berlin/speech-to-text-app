package stt.http

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The body of `GET /api/tasks/{id}/turns`. */
internal suspend fun HttpClient.turnsOf(id: Long): JsonObject = Json.parseToJsonElement(get("/api/tasks/$id/turns").bodyAsText()).jsonObject
