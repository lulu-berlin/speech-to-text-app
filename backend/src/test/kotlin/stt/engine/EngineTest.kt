package stt.engine

import io.kotest.core.TestConfiguration
import io.kotest.engine.spec.tempdir
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import stt.db.Database
import stt.db.TestDataDir

/**
 * Runs [block] with a freshly opened database, like [stt.db.withDatabase], but handing [block] the test's
 * own [TestScope]: the engine's worker must be launched in a scope the test controls, and `backgroundScope`
 * is reachable only from a `runTest` body, not from inside a lambda that has a receiver of its own.
 */
internal fun TestConfiguration.withEngineDatabase(block: suspend TestScope.(TestDataDir, Database) -> Unit) =
    runTest {
        val dataDir = tempdir("stt-engine").toPath()
        Database.open(dataDir).use { db -> this.block(TestDataDir(dataDir), db) }
    }
