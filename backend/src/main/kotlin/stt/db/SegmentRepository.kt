package stt.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import stt.queue.TaskStatus

/**
 * What an edit or a revert wrote (item 52): the stored segment, or why nothing was stored.
 */
sealed interface SegmentWriteOutcome {
    data class Stored(
        val segment: Segment,
    ) : SegmentWriteOutcome

    /** The task has no segment with that id (or a legacy row kept no original to go back to). */
    data object NoSegment : SegmentWriteOutcome

    /** The task left COMPLETED while the write was on its way — a retry started — so nothing is stored. */
    data object NotCompleted : SegmentWriteOutcome
}

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
            // ADR 0061: the first write keeps whisper's line, which Revert restores.
            this[SegmentsTable.originalText] = segment.originalText ?: segment.text
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
            .map { it.toSegment() }

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
     * Stores [text] on one segment of the task (item 52): an in-place update of that row's text and nothing else,
     * in one write transaction, then returns the stored row. The task's status is read in that same transaction:
     * once it has left COMPLETED (a retry started, root review 2026-10-23) nothing is stored and the outcome is
     * [SegmentWriteOutcome.NotCompleted]. [SegmentWriteOutcome.NoSegment] when the task has no segment [segmentId]
     * — a segment of another task is not addressable through this task. The caller (the edit route) has checked the
     * text.
     */
    suspend fun updateSegmentText(
        taskId: Long,
        segmentId: Long,
        text: String,
    ): SegmentWriteOutcome =
        db.writeTransaction {
            // The route checked the status too, but a retry can start while the edit is on its way: the
            // transcription it replaces must not be overwritten by an edit of the old one (root review
            // 2026-10-23).
            if (!taskIsCompleted(taskId)) return@writeTransaction SegmentWriteOutcome.NotCompleted
            val updated =
                SegmentsTable.update({ (SegmentsTable.id eq segmentId) and (SegmentsTable.taskId eq taskId) }) {
                    it[SegmentsTable.text] = text
                }
            if (updated != 1) return@writeTransaction SegmentWriteOutcome.NoSegment
            SegmentWriteOutcome.Stored(
                SegmentsTable
                    .selectAll()
                    .where { SegmentsTable.id eq segmentId }
                    .single()
                    .toSegment(),
            )
        }

    /**
     * Restores whisper's original text onto one segment of the task (ADR 0061): an in-place update of that row's
     * `text` from its `original_text`, the original itself untouched, in one write transaction, then returns the
     * stored row. Like an edit, it refuses once the task has left COMPLETED ([SegmentWriteOutcome.NotCompleted],
     * nothing stored), and answers [SegmentWriteOutcome.NoSegment] when the task has no segment [segmentId] (a
     * segment of another task is not addressable through this task) or a legacy row has no original. The caller
     * (the revert route) has checked the text.
     */
    suspend fun revertSegmentText(
        taskId: Long,
        segmentId: Long,
    ): SegmentWriteOutcome =
        db.writeTransaction {
            if (!taskIsCompleted(taskId)) return@writeTransaction SegmentWriteOutcome.NotCompleted
            val original =
                SegmentsTable
                    .select(SegmentsTable.originalText)
                    .where { (SegmentsTable.id eq segmentId) and (SegmentsTable.taskId eq taskId) }
                    .singleOrNull()
                    ?.get(SegmentsTable.originalText)
                    ?: return@writeTransaction SegmentWriteOutcome.NoSegment
            SegmentsTable.update({ SegmentsTable.id eq segmentId }) { it[text] = original }
            SegmentWriteOutcome.Stored(
                SegmentsTable
                    .selectAll()
                    .where { SegmentsTable.id eq segmentId }
                    .single()
                    .toSegment(),
            )
        }

    /** Whether [taskId] is COMPLETED, read inside the caller's transaction. */
    private fun taskIsCompleted(taskId: Long): Boolean =
        TasksTable
            .select(TasksTable.status)
            .where { TasksTable.id eq taskId }
            .singleOrNull()
            ?.get(TasksTable.status) == TaskStatus.COMPLETED

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
     * Refused as [MergeOutcome.Invalid], storing nothing, when [into] is not in the group, the group names fewer than
     * two speakers, or a member (or [into]) is already merged into another speaker; the check reads the mapping in
     * the same transaction that writes it.
     */
    suspend fun mergeSpeakers(
        taskId: Long,
        group: List<Int>,
        into: Int,
    ): MergeOutcome =
        db.writeTransaction {
            val members = group.distinct()
            if (into !in members) return@writeTransaction MergeOutcome.Invalid("into $into is not in the group $members")
            if (members.size < 2) return@writeTransaction MergeOutcome.Invalid("a merge needs at least two speakers, got $members")
            val merged = selectMerges(taskId)
            members.firstOrNull { it in merged }?.let {
                return@writeTransaction MergeOutcome.Invalid("speaker $it is already merged into another speaker")
            }
            val folded = members.filter { it != into }
            // A folded member may itself be the survivor of an earlier merge: its merged speakers follow it into
            // [into], so the mapping stays flat (user decision 2026-10-10) and one lookup still resolves a speaker.
            SpeakerMergeTable.update({ (SpeakerMergeTable.taskId eq taskId) and (SpeakerMergeTable.intoId inList folded) }) {
                it[intoId] = into
            }
            SpeakerMergeTable.batchInsert(folded, shouldReturnGeneratedValues = false) { member ->
                this[SpeakerMergeTable.taskId] = taskId
                this[SpeakerMergeTable.mergedId] = member
                this[SpeakerMergeTable.intoId] = into
            }
            MergeOutcome.Merged
        }

    /**
     * Removes every merge of the task (item 22, "Reset all merges"): the task's `speaker_merge` rows go in one write
     * transaction, so every speaker is effective again with the name its row kept, and the segments read as stored.
     */
    suspend fun resetMerges(taskId: Long) {
        db.writeTransaction { SpeakerMergeTable.deleteWhere { SpeakerMergeTable.taskId eq taskId } }
    }

    /**
     * Moves the task's segments [segmentIds] to speaker [speakerId] (item 73's amendment), in one write transaction. An
     * existing [speakerId] takes them as they are, and no speaker row is added; a null [speakerId] is a new speaker: a
     * `speakers` row with the task's highest speaker id + 1 over all its rows, named with the first `"Speaker N"` from
     * N = id + 1 that no speaker of the task has in any letter case.
     */
    suspend fun reassignSegments(
        taskId: Long,
        segmentIds: List<Long>,
        speakerId: Int?,
    ): ReassignOutcome =
        db.writeTransaction {
            val selected = segmentIds.toSet()
            if (selected.isEmpty()) return@writeTransaction ReassignOutcome.Invalid("segmentIds names no segment")
            val rows = selectSegments(taskId)
            val intoOf = selectMerges(taskId)
            // The target is re-checked here, in the same transaction that rewrites the rows: one that a merge made
            // unknown since the route's look must refuse the move, never fail it on the segment's foreign key.
            if (speakerId != null) {
                val targets = selectSpeakers(taskId).map { it.speakerId }.toSet()
                if (speakerId !in targets || speakerId in intoOf) return@writeTransaction ReassignOutcome.UnknownSpeaker
            }
            val own = rows.mapNotNull { it.id }.toSet()
            (selected - own).firstOrNull()?.let {
                return@writeTransaction ReassignOutcome.Invalid("task $taskId has no segment $it")
            }
            // A move that changes nothing: every selected segment already belongs to the target, as readers see it
            // (through the merge mapping, since no merge has been baked yet).
            if (speakerId != null) {
                val unchanged =
                    rows
                        .filter { it.id in selected }
                        .all { (intoOf[it.speakerId] ?: it.speakerId) == speakerId }
                if (unchanged) {
                    return@writeTransaction ReassignOutcome.Invalid("every selected segment already belongs to speaker $speakerId")
                }
            }
            // The new speaker's id is read over all rows before the bake-in and any removal (user decision 2026-10-31).
            val target = speakerId ?: selectSpeakers(taskId).maxOf { it.speakerId } + 1
            bakeMerges(taskId, intoOf)
            if (speakerId == null) insertNewSpeaker(taskId, target)
            SegmentsTable.update({ (SegmentsTable.taskId eq taskId) and (SegmentsTable.id inList segmentIds) }) {
                it[SegmentsTable.speakerId] = target
            }
            deleteEmptySpeakers(taskId)
            ReassignOutcome.Reassigned
        }

    /**
     * Deletes the speaker rows of task [taskId] that no segment references, inside the caller's write transaction (item
     * 73's amendment: a speaker left without segments loses its row). Its id was counted by [insertNewSpeaker] before
     * the removal, so it is never handed out again.
     */
    private fun deleteEmptySpeakers(taskId: Long) {
        val used =
            SegmentsTable
                .select(SegmentsTable.speakerId)
                .where { (SegmentsTable.taskId eq taskId) and SegmentsTable.speakerId.isNotNull() }
                .mapNotNull { it[SegmentsTable.speakerId] }
                .toSet()
        if (used.isEmpty()) return
        SpeakersTable.deleteWhere { (SpeakersTable.taskId eq taskId) and (SpeakersTable.speakerId notInList used) }
    }

    /**
     * Adds speaker [speakerId] to task [taskId], inside the caller's write transaction: its name is the first
     * `"Speaker N"` from N = id + 1 that no speaker of the task has in any letter case. The id itself is chosen by the
     * caller, before any removal.
     */
    private fun insertNewSpeaker(
        taskId: Long,
        speakerId: Int,
    ) {
        val speakers = selectSpeakers(taskId)
        val name =
            generateSequence(speakerId) { it + 1 }
                .map(::defaultSpeakerName)
                .first { candidate -> speakers.none { it.speaker.equals(candidate, ignoreCase = true) } }
        SpeakersTable.insert {
            it[SpeakersTable.taskId] = taskId
            it[SpeakersTable.speakerId] = speakerId
            it[speaker] = name
        }
    }

    /**
     * Makes task [taskId]'s merges permanent, inside the caller's write transaction (item 73): every merged speaker's
     * segments are stored under its survivor, and its `speaker_merge` row and its `speakers` row are deleted, so the
     * transcript reads the same while "Reset all merges" has nothing left to undo. The mapping is flat, so one pass
     * over [intoOf] suffices. Tasks without merges cost nothing.
     */
    private fun bakeMerges(
        taskId: Long,
        intoOf: Map<Int, Int>,
    ) {
        if (intoOf.isEmpty()) return
        for ((merged, into) in intoOf) {
            SegmentsTable.update({ (SegmentsTable.taskId eq taskId) and (SegmentsTable.speakerId eq merged) }) {
                it[SegmentsTable.speakerId] = into
            }
        }
        SpeakerMergeTable.deleteWhere { SpeakerMergeTable.taskId eq taskId }
        SpeakersTable.deleteWhere { (SpeakersTable.taskId eq taskId) and (SpeakersTable.speakerId inList intoOf.keys) }
    }

    /**
     * The task's transcript as readers see it (item 22, the one place the merge mapping is resolved): its segments
     * in (`chunk_idx`, `seq`) order with each merged `speakerId` replaced by the speaker it was merged into, and its
     * effective speakers, those not merged into another. One read transaction, so both agree with one mapping; the
     * mapping is flat, so one lookup resolves a speaker.
     */
    suspend fun findDiarized(taskId: Long): DiarizedRead =
        db.readTransaction {
            val intoOf = selectMerges(taskId)
            val segments = selectSegments(taskId).map { it.copy(speakerId = it.speakerId?.let { id -> intoOf[id] ?: id }) }
            val speakers = selectSpeakers(taskId).filter { it.speakerId !in intoOf }
            DiarizedRead(segments, speakers, hasMerges = intoOf.isNotEmpty())
        }

    /** The task's merge mapping, merged speaker id → the id it was merged into; runs inside the caller's transaction. */
    private fun selectMerges(taskId: Long): Map<Int, Int> =
        SpeakerMergeTable
            .selectAll()
            .where { SpeakerMergeTable.taskId eq taskId }
            .associate { it[SpeakerMergeTable.mergedId] to it[SpeakerMergeTable.intoId] }
}

/** A row of `speakers` as a [Speaker]. */
private fun ResultRow.toSpeaker() = Speaker(speakerId = this[SpeakersTable.speakerId], speaker = this[SpeakersTable.speaker])

/** A row of `segments` as a [Segment], carrying its row id. */
private fun ResultRow.toSegment() =
    Segment(
        chunkIdx = this[SegmentsTable.chunkIdx],
        seq = this[SegmentsTable.seq],
        startMs = this[SegmentsTable.startMs],
        endMs = this[SegmentsTable.endMs],
        text = this[SegmentsTable.text],
        speakerId = this[SegmentsTable.speakerId],
        id = this[SegmentsTable.id],
        originalText = this[SegmentsTable.originalText],
    )
