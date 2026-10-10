package stt.db

/**
 * What an upload spool decided (item 12): [Created] stores the task from the spooled bytes; [Duplicate]
 * reports the task that already holds the same bytes, so the route can answer `409 duplicate-audio` without
 * storing anything. The digest and size come from the spool pass itself, so no second read ever happens.
 */
sealed interface UploadOutcome {
    data class Created(
        val id: Long,
        val sha256: String,
        val sizeBytes: Long,
    ) : UploadOutcome

    data class Duplicate(
        val existingId: Long,
        val sha256: String,
        val sizeBytes: Long,
    ) : UploadOutcome
}
