package stt.db

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

class SegmentRepository(
    private val db: Database,
) {
    suspend fun insertAll(
        taskId: Long,
        segments: List<Segment>,
    ) {
        db.writeTransaction { insertSegments(taskId, segments) }
    }

    /**
     * Marks chunk [chunkIdx] of the task `DONE` and stores its [segments] in one transaction (ADR 0004 resume):
     * both or neither, so a chunk interrupted or failed before this point is re-run with no segments left behind.
     * A chunk that is not in the task's plan fails and stores nothing.
     */
    suspend fun completeChunk(
        taskId: Long,
        chunkIdx: Int,
        segments: List<Segment>,
    ) {
        db.writeTransaction {
            val marked =
                ChunksTable.update({ (ChunksTable.taskId eq taskId) and (ChunksTable.idx eq chunkIdx) }) {
                    it[status] = ChunkStatus.DONE
                }
            check(marked == 1) { "Chunk $chunkIdx of task $taskId is not in the task's plan" }
            insertSegments(taskId, segments)
        }
    }

    /** Inserts [segments] of the task; runs inside the caller's write transaction. */
    private fun insertSegments(
        taskId: Long,
        segments: List<Segment>,
    ) {
        SegmentsTable.batchInsert(segments, shouldReturnGeneratedValues = false) { segment ->
            this[SegmentsTable.taskId] = taskId
            this[SegmentsTable.chunkIdx] = segment.chunkIdx
            this[SegmentsTable.seq] = segment.seq
            this[SegmentsTable.startMs] = segment.startMs
            this[SegmentsTable.endMs] = segment.endMs
            this[SegmentsTable.text] = segment.text
            this[SegmentsTable.speaker] = segment.speaker
        }
    }

    /** The task's segments in (`chunk_idx`, `seq`) order; `seq` restarts in every chunk. */
    suspend fun findByTask(taskId: Long): List<Segment> =
        db.readTransaction {
            SegmentsTable
                .selectAll()
                .where { SegmentsTable.taskId eq taskId }
                .orderBy(SegmentsTable.chunkIdx to SortOrder.ASC, SegmentsTable.seq to SortOrder.ASC)
                .map { row ->
                    Segment(
                        chunkIdx = row[SegmentsTable.chunkIdx],
                        seq = row[SegmentsTable.seq],
                        startMs = row[SegmentsTable.startMs],
                        endMs = row[SegmentsTable.endMs],
                        text = row[SegmentsTable.text],
                        speaker = row[SegmentsTable.speaker],
                    )
                }
        }
}
