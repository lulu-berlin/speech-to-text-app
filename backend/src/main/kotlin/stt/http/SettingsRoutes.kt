package stt.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import stt.config.Device
import stt.config.dbValue
import stt.config.parseStoredDevice
import stt.db.Settings
import stt.db.SettingsRepository
import stt.db.SpeakersMode
import stt.engine.ProcessingEngine
import stt.transcribe.GpuDevice

/** The body of `GET /api/settings` and the `200` of `PUT /api/settings` (item 32's contract, `docs/api/settings*.json`). */
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

/** The body of `PUT /api/settings` and the `settings-updated` payload (item 32, `docs/api/settings-updated*.json`). */
@Serializable
data class SettingsEventJson(
    val device: String,
    val threads: Int?,
    val defaultLanguage: String,
    val speakersMode: String,
    val speakersCount: Int?,
    val maxSpeakers: Int,
)

/**
 * The settings endpoints (item 32): read them with the GPUs the startup probe reported as choices, or write them
 * as a whole — validated in contract order, stored, answered and published as `settings-updated`.
 */
fun Application.settingsRoutes(gpus: List<GpuDevice>) {
    val settings: SettingsRepository by dependencies
    val engine: ProcessingEngine by dependencies
    routing {
        get("/api/settings") {
            call.respond(settings.get().toJson(gpus))
        }

        put("/api/settings") {
            when (val request = call.receive<SettingsEventJson>().validate()) {
                is Validated.Rejected -> {
                    call.respondError(HttpStatusCode.BadRequest, request.code, request.message)
                }

                is Validated.Valid -> {
                    settings.update(request.settings)
                    engine.settingsUpdated(request.settings)
                    call.respond(request.settings.toJson(gpus))
                }
            }
        }
    }
}

/** A `PUT /api/settings` body checked against the contract: the [Settings] to store, or the first rejection. */
private sealed interface Validated {
    data class Valid(
        val settings: Settings,
    ) : Validated

    data class Rejected(
        val code: String,
        val message: String,
    ) : Validated
}

/** Validates in contract order: device, threads, language, speakers, max speakers; the first failure wins. */
private fun SettingsEventJson.validate(): Validated {
    val parsedDevice =
        runCatching { parseStoredDevice(device) }.getOrNull()
            ?: return Validated.Rejected(
                "invalid-device",
                "device must be auto, cpu, or gpu:<index> with an index >= 0, got \"$device\"",
            )
    if (threads != null && threads < 1) {
        return Validated.Rejected("invalid-threads", "threads must be null or a number >= 1, got $threads")
    }
    if (defaultLanguage !in LANGUAGES) {
        return Validated.Rejected("invalid-language", "defaultLanguage \"$defaultLanguage\" is not in languages.json")
    }
    val mode = SpeakersMode.entries.firstOrNull { it.dbValue == speakersMode }
    val countFits = if (mode == SpeakersMode.FIXED) speakersCount in 1..MAX_SPEAKERS else speakersCount == null
    if (mode == null || !countFits) {
        return Validated.Rejected(
            "invalid-speakers",
            "speakersMode must be auto, off or fixed, with a speakersCount between 1 and $MAX_SPEAKERS only for fixed; " +
                "got \"$speakersMode\" with $speakersCount",
        )
    }
    if (maxSpeakers !in 1..MAX_SPEAKERS) {
        return Validated.Rejected("invalid-max-speakers", "maxSpeakers must be a number between 1 and $MAX_SPEAKERS, got $maxSpeakers")
    }
    return Validated.Valid(Settings(parsedDevice, threads, defaultLanguage, mode, speakersCount, maxSpeakers))
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
        availableDevices = listOf(Device.Cpu.dbValue(), Device.Auto.dbValue()) + gpus.map { Device.Gpu(it.index).dbValue() },
    )

/** The stored settings as the `settings-updated` payload: the settings without the device choices. */
internal fun Settings.toEventJson(): SettingsEventJson =
    SettingsEventJson(
        device = device.dbValue(),
        threads = threads,
        defaultLanguage = defaultLanguage,
        speakersMode = speakersMode.dbValue,
        speakersCount = speakersCount,
        maxSpeakers = defaultMaxSpeakers,
    )
