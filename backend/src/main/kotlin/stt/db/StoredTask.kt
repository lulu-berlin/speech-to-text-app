package stt.db

/** A saved task together with its database id. */
data class StoredTask(
    val id: Long,
    val task: Task,
)
