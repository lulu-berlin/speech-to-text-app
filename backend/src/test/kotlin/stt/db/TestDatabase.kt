package stt.db

import io.kotest.core.TestConfiguration
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.test.runTest

/** Runs [block] against a freshly opened database in a per-test temp directory. */
internal fun TestConfiguration.withDatabase(block: suspend (Database) -> Unit) =
    runTest {
        val dataDir = tempdir("stt-db").toPath()
        Database.open(dataDir).use { db ->
            block(db)
        }
    }

/** Number of rows in [table], read over the raw connection. */
internal fun rowCount(
    db: Database,
    table: String,
): Int =
    db.connection.createStatement().use { statement ->
        statement.executeQuery("SELECT count(*) FROM \"$table\"").use { rows ->
            rows.next().shouldBeTrue()
            rows.getInt(1)
        }
    }
