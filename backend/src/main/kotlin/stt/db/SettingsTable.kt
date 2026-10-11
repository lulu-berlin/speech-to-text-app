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

    const val ONLY_ROW_ID = 1

    override val primaryKey = PrimaryKey(id)
}
