package stt.db

import stt.audio.PlannedChunk

// Seeding helpers for states the app writes only as part of a whole chunking result (ChunkRepository.storeChunked):
// a plan or a clip on its own, as a spec needs it — including the partial states an interrupted run of an earlier
// version could leave.

/** Stores [taskId]'s chunk plan alone: a chunk's `idx` is its position in [chunks], every chunk PENDING. */
internal suspend fun storePlan(
    db: Database,
    taskId: Long,
    chunks: List<PlannedChunk>,
) = db.writeTransaction { insertPlan(taskId, chunks) }

/** Stores [taskId]'s canonical clip header alone, without its rows. */
internal suspend fun storeHeader(
    db: Database,
    taskId: Long,
    header: ByteArray,
) = db.writeTransaction { insertHeader(taskId, header) }

/** Stores [taskId]'s canonical clip, its [header] and its one-second [rows], without a plan. */
internal suspend fun storeClip(
    db: Database,
    taskId: Long,
    header: ByteArray,
    rows: List<CanonicalAudio>,
) = db.writeTransaction {
    insertHeader(taskId, header)
    replaceCanonicalRows(taskId, rows)
}
