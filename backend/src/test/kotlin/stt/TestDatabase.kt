package stt

import java.nio.file.Path
import java.sql.DriverManager

/**
 * Runs [sql]'s statement on its own connection to [dataDir]'s `stt.db`, so it commits: a statement run on the app's
 * writer connection can still belong to the transaction Exposed opened, and would be rolled back when the Database
 * closes (item 95's recovery and upload specs).
 */
internal fun executeSql(
    dataDir: Path,
    sql: String,
) {
    DriverManager.getConnection("jdbc:sqlite:${dataDir.resolve("stt.db")}").use { connection ->
        connection.createStatement().use { it.executeUpdate(sql) }
    }
}
