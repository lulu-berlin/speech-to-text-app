package stt.db

/**
 * A saved task with the facts the API reports beside its row (item 12's contract), none of them a column of
 * `tasks`: the size of its upload, whether its chunk FLACs are stored ([hasAudio]), and the end of its last
 * stored segment ([transcribedUntilMs], null while none is stored).
 */
data class TaskDetails(
    val id: Long,
    val task: Task,
    val sizeBytes: Long,
    val hasAudio: Boolean,
    val transcribedUntilMs: Long?,
)
