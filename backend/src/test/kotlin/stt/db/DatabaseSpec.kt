package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.ArrayList

class DatabaseSpec :
    BehaviorSpec({

        given("an empty data directory") {
            `when`("opening the database for the first time") {
                then("stt.db is created, V1 applies, and a reopen applies nothing and preserves rows") {
                    runTest {
                        val dataDir = tempdir("stt-db").toPath()

                        Database.open(dataDir).use { first ->
                            tableNames(first).shouldContainAll(expectedTables)
                            migratedCount(first).shouldBe(1)
                            first.connection
                                .createStatement()
                                .use { st ->
                                    st.executeUpdate(
                                        "INSERT INTO tasks " +
                                            "(original_filename, content_type, status, speakers_mode, created_at) " +
                                            "VALUES ('a.wav', 'audio/wav', 'QUEUED', 'auto', 1)",
                                    )
                                }
                        }

                        Files.exists(dataDir.resolve("stt.db")).shouldBeTrue()

                        Database.open(dataDir).use { second ->
                            rowCount(second, "tasks").shouldBe(1)
                            migratedCount(second).shouldBe(1)
                        }
                    }
                }
            }
        }
    })

private val expectedTables =
    listOf(
        "tasks",
        "audio",
        "audio_parts",
        "chunks",
        "segments",
        "queue_state",
        "flyway_schema_history",
    )

private fun tableNames(db: Database): List<String> =
    db.connection.createStatement().use { statement ->
        statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { names ->
            val tables = ArrayList<String>()
            while (names.next()) {
                tables.add(names.getString(1))
            }
            tables
        }
    }

private fun migratedCount(db: Database): Int =
    db.connection.createStatement().use { statement ->
        statement.executeQuery("SELECT count(*) FROM flyway_schema_history").use { rows ->
            rows.next().shouldBeTrue()
            rows.getInt(1)
        }
    }

private fun rowCount(
    db: Database,
    table: String,
): Int =
    db.connection.createStatement().use { statement ->
        statement.executeQuery("SELECT count(*) FROM \"$table\"").use { rows ->
            rows.next().shouldBeTrue()
            rows.getInt(1)
        }
    }
