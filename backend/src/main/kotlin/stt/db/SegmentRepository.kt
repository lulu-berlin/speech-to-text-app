package stt.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
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
            this[SegmentsTable.speakerId] = segment.speakerId
        }
    }

    /** The task's segments in (`chunk_idx`, `seq`) order; `seq` restarts in every chunk. */
    suspend fun findByTask(taskId: Long): List<Segment> = db.readTransaction { selectSegments(taskId) }

    /** The task's segments in (`chunk_idx`, `seq`) order; runs inside the caller's transaction. */
    private fun selectSegments(taskId: Long): List<Segment> =
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
                    speakerId = row[SegmentsTable.speakerId],
                )
            }

    /**
     * Stores the speaker of every segment of the task in one transaction (item 09, ADR 0028): one [Speaker] per
     * distinct id in [speakerIds], named "Speaker N" (N = speakerId + 1), and each segment's `speaker_id`, given in
     * [findByTask] order. A count that differs from the task's segment count fails and stores nothing. Storing again
     * replaces the task's previous speakers.
     */
    suspend fun storeSpeakers(
        taskId: Long,
        speakerIds: List<Int>,
    ) {
        db.writeTransaction {
            val segmentIds =
                SegmentsTable
                    .select(SegmentsTable.id)
                    .where { SegmentsTable.taskId eq taskId }
                    .orderBy(SegmentsTable.chunkIdx to SortOrder.ASC, SegmentsTable.seq to SortOrder.ASC)
                    .map { it[SegmentsTable.id] }
            check(segmentIds.size == speakerIds.size) {
                "Task $taskId has ${segmentIds.size} segments, but ${speakerIds.size} speaker ids were given"
            }
            // Replace, not insert-only, for one failure case: the engine crashes after this transaction commits but
            // before the task is marked COMPLETED, so the resumed task (ADR 0004) diarizes again and stores the same
            // speakers (ADR 0024) a second time, which would otherwise fail on the speakers primary key. Speakers are
            // renamed only on completed tasks, so no rename is lost.
            SegmentsTable.update({ SegmentsTable.taskId eq taskId }) { it[SegmentsTable.speakerId] = null }
            SpeakersTable.deleteWhere { SpeakersTable.taskId eq taskId }
            SpeakersTable.batchInsert(speakerIds.distinct().sorted(), shouldReturnGeneratedValues = false) { speakerId ->
                this[SpeakersTable.taskId] = taskId
                this[SpeakersTable.speakerId] = speakerId
                this[SpeakersTable.speaker] = defaultSpeakerName(speakerId)
            }
            segmentIds.zip(speakerIds).forEach { (segmentId, speakerId) ->
                SegmentsTable.update({ SegmentsTable.id eq segmentId }) { it[SegmentsTable.speakerId] = speakerId }
            }
        }
    }

    /**
     * Renames one speaker of the task (item 22): updates only that speaker's row, so no segment row and no other
     * speaker is touched. Within the same write transaction, a name that another speaker of the task already has,
     * compared case-insensitively, is refused as [RenameOutcome.DuplicateName] and nothing is stored (user decisions
     * 2026-10-10). Every `speakers` row counts, a merged one included, so undoing merges never yields two speakers
     * with one name; the speaker's own current name is no duplicate. The caller (the rename route) has already
     * validated the name and the speaker's existence; a missing row here fails loudly.
     */
    suspend fun renameSpeaker(
        taskId: Long,
        speakerId: Int,
        name: String,
    ): RenameOutcome =
        db.writeTransaction {
            val clash = selectSpeakers(taskId).firstOrNull { it.speakerId != speakerId && it.speaker.equals(name, ignoreCase = true) }
            if (clash != null) return@writeTransaction RenameOutcome.DuplicateName(clash)
            val updated =
                SpeakersTable.update({ (SpeakersTable.taskId eq taskId) and (SpeakersTable.speakerId eq speakerId) }) {
                    it[speaker] = name
                }
            check(updated == 1) { "Task $taskId has no speaker $speakerId" }
            RenameOutcome.Renamed
        }

    /** The task's speakers in `speaker_id` order. */
    suspend fun findSpeakers(taskId: Long): List<Speaker> = db.readTransaction { selectSpeakers(taskId) }

    /** The task's speakers in `speaker_id` order; runs inside the caller's transaction. */
    private fun selectSpeakers(taskId: Long): List<Speaker> =
        SpeakersTable
            .selectAll()
            .where { SpeakersTable.taskId eq taskId }
            .orderBy(SpeakersTable.speakerId to SortOrder.ASC)
            .map { it.toSpeaker() }

    /**
     * Folds every speaker of [group] except [into] into [into] (item 22): one `speaker_merge` row each, in one write
     * transaction. No segment row and no `speakers` row is touched; [findDiarized] resolves the mapping at read time.
     */
    suspend fun mergeSpeakers(
        taskId: Long,
        group: List<Int>,
        into: Int,
    ) {
        db.writeTransaction {
            SpeakerMergeTable.batchInsert(group.filter { it != into }, shouldReturnGeneratedValues = false) { merged ->
                this[SpeakerMergeTable.taskId] = taskId
                this[SpeakerMergeTable.mergedId] = merged
                this[SpeakerMergeTable.intoId] = into
            }
        }
    }

    /**
     * The task's transcript as readers see it (item 22, the one place the merge mapping is resolved): its segments
     * in (`chunk_idx`, `seq`) order with each merged `speakerId` replaced by the speaker it was merged into, and its
     * effective speakers, those not merged into another. One read transaction, so both agree with one mapping; the
     * mapping is flat, so one lookup resolves a speaker.
     */
    suspend fun findDiarized(taskId: Long): DiarizedRead =
        db.readTransaction {
            val intoOf =
                SpeakerMergeTable
                    .selectAll()
                    .where { SpeakerMergeTable.taskId eq taskId }
                    .associate { it[SpeakerMergeTable.mergedId] to it[SpeakerMergeTable.intoId] }
            val segments = selectSegments(taskId).map { it.copy(speakerId = it.speakerId?.let { id -> intoOf[id] ?: id }) }
            val speakers = selectSpeakers(taskId).filter { it.speakerId !in intoOf }
            DiarizedRead(segments, speakers)
        }
}

/** A row of `speakers` as a [Speaker]. */
private fun ResultRow.toSpeaker() = Speaker(speakerId = this[SpeakersTable.speakerId], speaker = this[SpeakersTable.speaker])
