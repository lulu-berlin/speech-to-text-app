package stt.db

import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import stt.config.Device
import stt.config.dbValue
import stt.config.parseStoredDevice

/**
 * The app settings (roadmap item 32): one `settings` row, seeded with the defaults by V6. The route layer
 * validates before storing; the repository only reads and writes the row whole.
 */
class SettingsRepository(
    private val db: Database,
) {
    /** The current settings; the migration seeds the row, so a fresh database reads the defaults. */
    suspend fun get(): Settings =
        db.readTransaction {
            val row = SettingsTable.selectAll().single()
            Settings(
                device = parseStoredDevice(row[SettingsTable.device]),
                threads = row[SettingsTable.threads],
                defaultLanguage = row[SettingsTable.defaultLanguage],
                speakersMode = SpeakersMode.fromDbValue(row[SettingsTable.speakersMode]),
                speakersCount = row[SettingsTable.speakersCount],
                defaultMaxSpeakers = row[SettingsTable.maxSpeakers],
                deviceFallback = row[SettingsTable.deviceFallback]?.let { parseStoredDevice(it) },
            )
        }

    /** Stores [settings] as the single row. */
    suspend fun update(settings: Settings) {
        db.writeTransaction {
            SettingsTable.upsert {
                it[SettingsTable.id] = SettingsTable.ONLY_ROW_ID
                it[SettingsTable.device] = settings.device.dbValue()
                it[SettingsTable.threads] = settings.threads
                it[SettingsTable.defaultLanguage] = settings.defaultLanguage
                it[SettingsTable.speakersMode] = settings.speakersMode.dbValue
                it[SettingsTable.speakersCount] = settings.speakersCount
                it[SettingsTable.maxSpeakers] = settings.defaultMaxSpeakers
                it[SettingsTable.deviceFallback] = settings.deviceFallback?.dbValue()
            }
        }
    }

    /**
     * The engine found the chosen [gpu] unusable (item 32): stores the device as CPU and the notice naming [gpu] in one
     * write, leaving every other setting as it is, and returns the settings as they are now.
     */
    suspend fun fallBackToCpu(gpu: Device.Gpu): Settings {
        db.writeTransaction {
            SettingsTable.update {
                it[SettingsTable.device] = Device.Cpu.dbValue()
                it[SettingsTable.deviceFallback] = gpu.dbValue()
            }
        }
        return get()
    }

    /** Clears the CPU-fallback notice (item 32's dismiss) and returns the settings as they are now. */
    suspend fun dismissDeviceFallback(): Settings {
        db.writeTransaction { SettingsTable.update { it[SettingsTable.deviceFallback] = null } }
        return get()
    }
}
