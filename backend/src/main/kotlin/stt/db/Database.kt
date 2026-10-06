package stt.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * The application's SQLite database (ADR 0006, ADR 0017): one file at
 * `$STT_DATA_DIR/stt.db`, WAL mode, foreign keys on, Flyway migrations applied
 * on every open. The data directory is created when missing; failures name
 * STT_DATA_DIR. All JDBC work runs on Dispatchers.IO (AGENTS.md).
 */
class Database private constructor(
    val connection: Connection,
) : AutoCloseable {
    companion object {
        suspend fun open(dataDir: Path): Database =
            withContext(Dispatchers.IO) {
                Files.createDirectories(dataDir)
                val dbFile = dataDir.resolve("stt.db")
                val url = "jdbc:sqlite:$dbFile"
                val connection = DriverManager.getConnection(url)
                connection.createStatement().use { st -> st.execute("PRAGMA journal_mode = WAL") }
                connection.createStatement().use { st -> st.execute("PRAGMA foreign_keys = ON") }
                try {
                    Flyway
                        .configure()
                        .dataSource(url, null, null)
                        .load()
                        .migrate()
                } catch (e: Exception) {
                    connection.close()
                    throw e
                }
                Database(connection)
            }
    }

    override fun close() {
        connection.close()
    }
}
