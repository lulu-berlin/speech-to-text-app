package stt.db

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll

class SegmentRepository(
    private val db: Database,
) {
    suspend fun insertAll(
        taskId: Long,
        segments: List<Segment>,
    ) {
        db.transaction {
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
    }

    /** The task's segments in (`chunk_idx`, `seq`) order; `seq` restarts in every chunk. */
    suspend fun findByTask(taskId: Long): List<Segment> =
        db.transaction {
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
