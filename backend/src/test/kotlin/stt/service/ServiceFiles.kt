package stt.service

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

/** The durable `config.json` of the service in [dirs]. */
internal fun configFile(dirs: ServiceDirs): Path = dirs.configDir.resolve("config.json")

/** The ephemeral `runtime.json` of the service in [dirs]. */
internal fun runtimeFile(dirs: ServiceDirs): Path = dirs.runtimeDir.resolve("runtime.json")

/** The durable `config.json` as plain JSON. */
internal fun readConfig(dirs: ServiceDirs): JsonObject = readJson(configFile(dirs))

/** The ephemeral `runtime.json` as plain JSON. */
internal fun readRuntime(dirs: ServiceDirs): JsonObject = readJson(runtimeFile(dirs))

/**
 * A bootstrap file as plain JSON. The service specs read it by key name, not through the service's own classes, so a
 * renamed key fails them as it would fail the Rust client.
 */
internal fun readJson(file: Path): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

/** Whether [file] exists within 60 seconds: the service writes `runtime.json` once it answers. */
internal suspend fun awaitFile(file: Path): Boolean {
    val deadline = System.nanoTime() + 60.seconds.inWholeNanoseconds
    while (System.nanoTime() < deadline) {
        if (file.exists()) return true
        delay(100)
    }
    return false
}
