package stt.db

/** Stored in `tasks.diarization_stage` by name; null outside the DIARIZING phase (item 12's contract). */
enum class DiarizationStage {
    EMBEDDING,
    CLUSTERING,
}
