package stt.db

import io.kotest.core.TestConfiguration
import io.kotest.engine.spec.tempdir
import kotlinx.coroutines.test.runTest

/** Runs [block] against a freshly opened database in a per-test temp directory. */
internal fun TestConfiguration.withDatabase(block: suspend (Database) -> Unit) =
    runTest {
        val dataDir = tempdir("stt-db").toPath()
        Database.open(dataDir).use { db ->
            block(db)
        }
    }
