package stt.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.sql.SQLException

class QueueStateRepositorySpec :
    BehaviorSpec({

        given("the queue state") {
            `when`("the queue is paused, resumed, and paused again") {
                then("each read returns the last value written, and only one queue_state row can exist") {
                    withDatabase { db ->
                        val queue = QueueStateRepository(db)

                        queue.setPaused(true)
                        queue.isPaused().shouldBeTrue()
                        queue.setPaused(false)
                        queue.isPaused().shouldBeFalse()
                        queue.setPaused(true)
                        queue.isPaused().shouldBeTrue()

                        rowCount(db, "queue_state").shouldBe(1)
                        shouldThrow<SQLException> {
                            db.connection.createStatement().use { st ->
                                st.executeUpdate("INSERT INTO queue_state (id, paused) VALUES (2, 0)")
                            }
                        }
                    }
                }
            }
        }
    })
