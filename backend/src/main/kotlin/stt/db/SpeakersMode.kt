package stt.db

/** Stored in `tasks.speakers_mode` as [dbValue]. */
enum class SpeakersMode(
    val dbValue: String,
) {
    AUTO("auto"),
    OFF("off"),
    FIXED("fixed"),
    ;

    companion object {
        fun fromDbValue(value: String): SpeakersMode = entries.first { it.dbValue == value }
    }
}
