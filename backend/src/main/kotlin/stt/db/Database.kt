package stt.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * The application's SQLite database (ADR 0006, ADR 0017): one file at
 * `$STT_DATA_DIR/stt.db`, WAL mode, foreign keys on, Flyway migrations applied
 * on every open. The data directory is created when missing; failures name
 * STT_DATA_DIR. All JDBC work runs on Dispatchers.IO (AGENTS.md).
 */
class Database private constructor(
    val connection: Connection,
) : AutoCloseable {
    // Exposed closes the connection at the end of every transaction; it gets a
    // view whose close() is a no-op so the owned connection (and its pragmas) lives on.
    // No retries: a transaction body may consume a one-shot stream (audio upload), and a
    // retry after e.g. a UNIQUE violation would re-run it on a drained stream.
    private val exposed =
        ExposedDatabase.connect(
            { nonClosing(connection) },
            databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 },
        )

    /** Runs [block] as one Exposed transaction on the owned connection, on Dispatchers.IO. */
    internal suspend fun <T> transaction(block: JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            transaction(exposed) { block() }
        }

    companion object {
        private const val DATA_DIR_UNUSABLE_ERROR =
            "STT_DATA_DIR \"%s\" cannot be created or opened as the data directory: %s"

        suspend fun open(dataDir: Path): Database =
            withContext(Dispatchers.IO) {
                val url = "jdbc:sqlite:${dataDir.resolve("stt.db")}"
                val connection =
                    try {
                        Files.createDirectories(dataDir)
                        DriverManager.getConnection(url)
                    } catch (e: Exception) {
                        throw IllegalStateException(DATA_DIR_UNUSABLE_ERROR.format(dataDir, e.message), e)
                    }
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

private fun nonClosing(connection: Connection): Connection =
    Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, args ->
        if (method.name == "close") {
            Unit
        } else {
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    } as Connection
