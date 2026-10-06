package stt.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    internal val connection: Connection,
    // One connection carries one transaction at a time: a concurrent commit or rollback
    // would otherwise also commit or roll back the other caller's work. Not reentrant:
    // a transaction body must not call transaction() again. Injectable so a test can observe it.
    private val transactionLock: Mutex,
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

    /**
     * Runs [block] as one Exposed transaction on the owned connection, on Dispatchers.IO;
     * concurrent callers wait their turn (suspending, not blocking a thread).
     */
    internal suspend fun <T> transaction(block: JdbcTransaction.() -> T): T =
        transactionLock.withLock {
            withContext(Dispatchers.IO) {
                transaction(exposed) { block() }
            }
        }

    companion object {
        private const val DATA_DIR_UNUSABLE_ERROR =
            "STT_DATA_DIR \"%s\" cannot be created or opened as the data directory: %s"

        suspend fun open(dataDir: Path): Database = open(dataDir, Mutex())

        /** [open] with the transaction lock supplied by the caller (tests observe it). */
        internal suspend fun open(
            dataDir: Path,
            transactionLock: Mutex,
        ): Database =
            withContext(Dispatchers.IO) {
                val url = "jdbc:sqlite:${dataDir.resolve("stt.db")}"
                val connection = openConnection(dataDir, url)
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
                Database(connection, transactionLock)
            }

        /** Creates [dataDir] when missing, connects to [url], then applies the pragmas in ADR 0017 order. */
        private fun openConnection(
            dataDir: Path,
            url: String,
        ): Connection {
            val connection =
                try {
                    Files.createDirectories(dataDir)
                    DriverManager.getConnection(url)
                } catch (e: Exception) {
                    throw IllegalStateException(DATA_DIR_UNUSABLE_ERROR.format(dataDir, e), e)
                }
            connection.createStatement().use { st -> st.execute("PRAGMA journal_mode = WAL") }
            connection.createStatement().use { st -> st.execute("PRAGMA foreign_keys = ON") }
            return connection
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
