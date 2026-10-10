package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.sqlite.SQLiteJDBCLoader

class SqliteVersionSpec :
    BehaviorSpec({
        given("the resolved sqlite-jdbc driver") {
            `when`("asking it for its version") {
                then("it equals the catalog pin, 3.50.3.0") {
                    // Must equal the catalog pin — update both together.
                    SQLiteJDBCLoader.getVersion().shouldBe("3.50.3.0")
                }
            }
        }
    })
