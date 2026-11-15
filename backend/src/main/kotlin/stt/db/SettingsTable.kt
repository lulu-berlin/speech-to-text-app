package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of the single-row `settings` (schema owned by V6__settings.sql and V7, roadmap item 32). */
internal object SettingsTable : Table("settings") {
    /** Always [ONLY_ROW_ID]; the schema's CHECK rejects any other value. */
    val id = integer("id")
    val device = text("device")
    val threads = integer("threads").nullable()
    val defaultLanguage = text("default_language")
    val speakersMode = text("speakers_mode")
    val speakersCount = integer("speakers_count").nullable()
    val maxSpeakers = integer("max_speakers")
    val deviceFallback = text("device_fallback").nullable()
    val defaultFrontend = text("default_frontend").nullable()
    val terminalCommand = text("terminal_command").nullable()

    /** Roadmap item 88: the app's own language; null follows the system (V18). */
    val appLanguage = text("app_language").nullable()

    const val ONLY_ROW_ID = 1

    override val primaryKey = PrimaryKey(id)
}
