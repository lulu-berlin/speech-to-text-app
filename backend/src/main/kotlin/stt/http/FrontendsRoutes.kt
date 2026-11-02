package stt.http

import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/** One installed frontend as the API offers it (roadmap item 66): its id, its name and its launch kind. */
@Serializable
data class FrontendEntry(
    val id: String,
    val name: String,
    val kind: String,
)

/** The body of `GET /api/frontends`. */
@Serializable
data class FrontendsJson(
    val frontends: List<FrontendEntry>,
)

private val frontendsLogger = LoggerFactory.getLogger("stt.http.FrontendsRoutes")
private val FRONTEND_KINDS = setOf("browser", "gui", "terminal")

/**
 * The installed frontends (roadmap item 66, test-list item D7): the service reads the same per-user registry the tray
 * and `stt-app` read, so the Settings page can offer them as the default. In dev/server mode there is no registry
 * ([dir] null) and the answer is empty.
 */
fun Application.frontendsRoutes(dir: Path?) {
    routing {
        get("/api/frontends") {
            call.respond(FrontendsJson(withContext(Dispatchers.IO) { readFrontends(dir) }))
        }
    }
}

/**
 * Reads every `*.json` descriptor in [dir], in file-name order. A file that does not parse, or whose `kind` is not a
 * frontend kind, is skipped with a warning and never hides the others.
 */
internal fun readFrontends(dir: Path?): List<FrontendEntry> {
    if (dir == null || !Files.isDirectory(dir)) return emptyList()
    val files =
        Files.list(dir).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.sorted().toList()
        }
    return files.mapNotNull { file ->
        try {
            val json = Json.parseToJsonElement(Files.readString(file)).jsonObject
            entryOf(json)
        } catch (e: Exception) {
            frontendsLogger.warn("Skipping unreadable frontend descriptor $file", e)
            null
        }
    }
}

/** [json] as an entry, or null when it is not a descriptor this API offers. */
private fun entryOf(json: JsonObject): FrontendEntry? {
    val id = json["id"]?.jsonPrimitive?.contentOrNull ?: return null
    val name = json["name"]?.jsonPrimitive?.contentOrNull ?: return null
    val kind = json["kind"]?.jsonPrimitive?.contentOrNull ?: return null
    if (kind !in FRONTEND_KINDS) return null
    return FrontendEntry(id, name, kind)
}
