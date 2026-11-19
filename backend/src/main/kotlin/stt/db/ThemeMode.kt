package stt.db

/** The theme every frontend draws in (roadmap item 67, ADR 0068); stored in `settings.theme_mode` as [dbValue]. */
enum class ThemeMode(
    val dbValue: String,
) {
    AUTO("auto"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromDbValue(value: String): ThemeMode = entries.first { it.dbValue == value }
    }
}
