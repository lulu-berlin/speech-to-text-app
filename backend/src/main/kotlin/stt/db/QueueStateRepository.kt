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

    suspend fun isPaused(): Boolean =
        db.transaction {
            QueueStateTable.selectAll().single()[QueueStateTable.paused]
        }
}
