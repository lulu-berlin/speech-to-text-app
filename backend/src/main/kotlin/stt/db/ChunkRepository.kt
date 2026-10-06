package stt.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll

class ChunkRepository(
    private val db: Database,
) {
    suspend fun insertAll(
        taskId: Long,
        chunks: List<Chunk>,
    ) {
        db.writeTransaction {
            ChunksTable.batchInsert(chunks, shouldReturnGeneratedValues = false) { chunk ->
                this[ChunksTable.taskId] = taskId
                this[ChunksTable.idx] = chunk.idx
                this[ChunksTable.startMs] = chunk.startMs
                this[ChunksTable.endMs] = chunk.endMs
                this[ChunksTable.status] = chunk.status
            }
        }
    }

    /** The task's chunks in `idx` order. */
    suspend fun findByTask(taskId: Long): List<Chunk> =
        db.readTransaction {
            ChunksTable
                .selectAll()
                .where { ChunksTable.taskId eq taskId }
                .orderBy(ChunksTable.idx)
                .map { row ->
                    Chunk(
                        idx = row[ChunksTable.idx],
                        startMs = row[ChunksTable.startMs],
                        endMs = row[ChunksTable.endMs],
                        status = row[ChunksTable.status],
                    )
                }
        }
}
