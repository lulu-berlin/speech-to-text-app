package stt.http

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import stt.config.dbValue
import stt.db.Settings
import stt.db.SettingsRepository
import stt.transcribe.GpuDevice

/** The body of `GET /api/settings` (item 32's contract, `docs/api/settings.json`): the stored settings and the devices to choose from. */
@Serializable
data class SettingsJson(
    val device: String,
    val threads: Int?,
    val defaultLanguage: String,
    val speakersMode: String,
    val speakersCount: Int?,
    val maxSpeakers: Int,
    val availableDevices: List<String>,
)

/** The settings endpoint (item 32): the stored settings plus the GPUs the startup probe reported, as choices. */
fun Application.settingsRoutes(gpus: List<GpuDevice>) {
    val settings: SettingsRepository by dependencies
    routing {
        get("/api/settings") {
            call.respond(settings.get().toJson(gpus))
        }
    }
}

/** The stored settings as the contract's JSON; the choices are `cpu` and `auto` always, then each of [gpus] as `gpu:<index>`. */
internal fun Settings.toJson(gpus: List<GpuDevice>): SettingsJson =
    SettingsJson(
        device = device.dbValue(),
        threads = threads,
        defaultLanguage = defaultLanguage,
        speakersMode = speakersMode.dbValue,
        speakersCount = speakersCount,
        maxSpeakers = defaultMaxSpeakers,
        availableDevices = listOf("cpu", "auto") + gpus.map { "gpu:${it.index}" },
    )
