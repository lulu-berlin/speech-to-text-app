package stt.db

import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.expandArgs
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Records the SQL statements Exposed executes, per database (item 71). Exposed loads it through
 * `META-INF/services/org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor` in the test resources;
 * [recordStatements] reads the statements of one test's data directory only, so specs running alongside do not add
 * to them.
 */
class StatementCounter : GlobalStatementInterceptor {
    override fun beforeExecution(
        transaction: Transaction,
        context: StatementContext,
    ) {
        val url = (transaction as? JdbcTransaction)?.db?.url ?: return
        recorded.forEach { (dbFile, statements) -> if (url.endsWith(dbFile)) statements += context.expandArgs(transaction) }
    }

    companion object {
        private val recorded = ConcurrentHashMap<String, MutableList<String>>()

        /** [block]'s result and the number of SQL statements it executes against the database in [dataDir]. */
        suspend fun <T> countStatements(
            dataDir: Path,
            block: suspend () -> T,
        ): Pair<T, Int> = recordStatements(dataDir, block).let { (result, statements) -> result to statements.size }

        /** [block]'s result and the SQL statements it executes against the database in [dataDir], arguments inlined. */
        suspend fun <T> recordStatements(
            dataDir: Path,
            block: suspend () -> T,
        ): Pair<T, List<String>> {
            val key = dataDir.resolve("stt.db").toString()
            val statements = Collections.synchronizedList(mutableListOf<String>())
            check(recorded.putIfAbsent(key, statements) == null) { "already recording statements in $key" }
            val result =
                try {
                    block()
                } finally {
                    recorded.remove(key)
                }
            return result to statements.toList()
        }
    }
}
