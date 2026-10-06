package stt.db

import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

class QueueStateRepository(
    private val db: Database,
) {
    suspend fun setPaused(paused: Boolean) {
        db.transaction {
            QueueStateTable.upsert {
                it[id] = QueueStateTable.ONLY_ROW_ID
                it[QueueStateTable.paused] = paused
            }
        }
    }

    /** False until the queue was first paused: a fresh database has no `queue_state` row yet. */
    suspend fun isPaused(): Boolean =
        db.readTransaction {
            QueueStateTable.selectAll().singleOrNull()?.get(QueueStateTable.paused) ?: false
        }
}
