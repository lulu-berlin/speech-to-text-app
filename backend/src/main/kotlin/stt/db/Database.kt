package stt.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.sqlite.SQLiteConfig
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
 *
 * One writer connection carries the write transactions, one at a time; every read
 * transaction opens its own read-only connection, which WAL lets run alongside the writer.
 */
class Database private constructor(
    /**
     * The writer connection, exposed for tests (raw SQL, PRAGMA checks) — test-only access by
     * convention. `internal` does not enforce this: the backend is one Gradle module, so all app
     * code can see it. App code must go through the repositories, which take the write lock.
     */
    internal val connection: Connection,
    url: String,
    /** `$STT_DATA_DIR/tmp`: upload spool files (ADR 0018) live here until their write transaction ends. */
    internal val tmpDir: Path,
    // One connection carries one transaction at a time: a concurrent commit or rollback
    // would otherwise also commit or roll back the other caller's work. Not reentrant:
    // a transaction body must not call writeTransaction() again. Injectable so a test can observe it.
    private val transactionLock: Mutex,
) : AutoCloseable {
    // Exposed closes the connection at the end of every transaction; it gets a
    // view whose close() is a no-op so the owned connection (and its pragmas) lives on.
    // No retries: a constraint violation (e.g. a duplicate sha256) is final, not transient.
    private val writer =
        ExposedDatabase.connect(
            { nonClosing(connection) },
            databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 },
        )

    // A fresh read-only connection per read transaction; Exposed closes it when the
    // transaction ends. No retries either: a read body may write to a one-shot target stream.
    private val readers =
        ExposedDatabase.connect(
            { DriverManager.getConnection(url, SQLiteConfig().apply { setReadOnly(true) }.toProperties()) },
            databaseConfig =
                DatabaseConfig {
                    defaultMaxAttempts = 1
                    defaultReadOnly = true
                },
        )

    /**
     * Runs [block] as one write transaction on the writer connection, on Dispatchers.IO;
     * concurrent callers wait their turn (suspending, not blocking a thread).
     */
    internal suspend fun <T> writeTransaction(block: JdbcTransaction.() -> T): T =
        transactionLock.withLock {
            withContext(Dispatchers.IO) {
                transaction(writer) { block() }
            }
        }

    /** Runs [block] as one read transaction on its own read-only connection, on Dispatchers.IO; never waits for writes. */
    internal suspend fun <T> readTransaction(block: JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            transaction(readers) { block() }
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
                val tmpDir = dataDir.resolve("tmp")
                val connection = openConnection(dataDir, tmpDir, url)
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
                Database(connection, url, tmpDir, transactionLock)
            }

        /**
         * Creates [dataDir] and [tmpDir] when missing, removes the spool files a crashed upload left
         * in [tmpDir], connects to [url], then applies the pragmas in ADR 0017 order.
         */
        private fun openConnection(
            dataDir: Path,
            tmpDir: Path,
            url: String,
        ): Connection {
            val connection =
                try {
                    Files.createDirectories(tmpDir)
                    Files.list(tmpDir).use { stale -> stale.forEach(Files::delete) }
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
