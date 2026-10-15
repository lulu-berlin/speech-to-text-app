package stt.db

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import stt.audio.PlannedChunk

class ChunkRepository(
    private val db: Database,
) {
    /**
     * Stores a chunking run's whole result in one write transaction (item 44): [taskId]'s chunk windows and the
     * canonical clip's [header] and one-second [rows]. Whatever an interrupted earlier run stored for the task is
     * replaced, so a stored plan always comes with its whole clip.
     */
    suspend fun storeChunked(
        taskId: Long,
        chunks: List<PlannedChunk>,
        header: ByteArray,
        rows: List<CanonicalAudio>,
    ) {
        db.writeTransaction {
            ChunksTable.deleteWhere { ChunksTable.taskId eq taskId }
            AudioHeaderTable.deleteWhere { AudioHeaderTable.taskId eq taskId }
            insertPlan(taskId, chunks)
            insertHeader(taskId, header)
            replaceCanonicalRows(taskId, rows)
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

/** Inserts [taskId]'s plan inside the caller's transaction: a chunk's `idx` is its position in [chunks], every chunk starts PENDING. */
internal fun JdbcTransaction.insertPlan(
    taskId: Long,
    chunks: List<PlannedChunk>,
) {
    chunks.forEachIndexed { idx, chunk ->
        ChunksTable.insert {
            it[ChunksTable.taskId] = taskId
            it[ChunksTable.idx] = idx
            it[startMs] = chunk.startMs
            it[endMs] = chunk.endMs
            it[status] = ChunkStatus.PENDING
        }
    }
}
