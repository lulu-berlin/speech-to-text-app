package stt.db

import io.kotest.core.TestConfiguration
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path

/** The per-test data directory a [withDatabase] block runs in. */
internal class TestDataDir(
    val dataDir: Path,
)

/** Runs [block] against a freshly opened database in a per-test temp directory, which [block] gets as [TestDataDir]. */
internal fun TestConfiguration.withDatabase(block: suspend TestDataDir.(Database) -> Unit) =
    runTest {
        val dataDir = tempdir("stt-db").toPath()
        Database.open(dataDir).use { db ->
            TestDataDir(dataDir).block(db)
        }
    }

/** The entries of [dir], in no particular order; none when [dir] does not exist. */
internal fun entries(dir: Path): List<Path> = if (Files.exists(dir)) Files.list(dir).use { it.toList() } else emptyList()

/** Number of rows in [table], read over the raw connection. */
internal fun rowCount(
    db: Database,
    table: String,
): Int = singleValue(db, "SELECT count(*) FROM \"$table\"").toInt()

/** The first column of the first row [sql] returns, read over the raw connection. */
internal fun singleValue(
    db: Database,
    sql: String,
): String =
    db.connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            rows.next().shouldBeTrue()
            rows.getString(1)
        }
    }
