package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.sqlite.SQLiteJDBCLoader

class SqliteVersionSpec :
    BehaviorSpec({
        given("the resolved sqlite-jdbc driver") {
            `when`("asking it for its version") {
                then("it equals the catalog pin, 3.54.1.0") {
                    // Must equal the catalog pin — update both together.
                    SQLiteJDBCLoader.getVersion().shouldBe("3.54.1.0")
                }
            }
            `when`("looking its driver class up on the classpath") {
                then("exactly one sqlite-jdbc copy carries it") {
                    // ADR 0042: the force leaves one sqlite-jdbc version; a second jar would make the
                    // version above depend on classpath order.
                    val copies = SQLiteJDBCLoader::class.java.classLoader.getResources("org/sqlite/JDBC.class")
                    copies.toList().shouldHaveSize(1)
                }
            }
        }
    })
