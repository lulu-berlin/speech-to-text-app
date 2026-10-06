package stt.db

import org.jetbrains.exposed.v1.core.Table

/** Exposed mapping of the single-row `queue_state` (schema owned by V1__init.sql). */
internal object QueueStateTable : Table("queue_state") {
    /** Always [ONLY_ROW_ID]; the schema's CHECK rejects any other value. */
    val id = integer("id")
    val paused = bool("paused")

    const val ONLY_ROW_ID = 1

    override val primaryKey = PrimaryKey(id)
}
