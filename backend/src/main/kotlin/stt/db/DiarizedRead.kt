package stt.db

/**
 * A task's transcript as its readers see it (item 22): [segments] in (`chunk_idx`, `seq`) order with every
 * `speakerId` resolved through the task's merges, and the task's effective [speakers] (every speaker not
 * merged into another), in `speakerId` order. Both come from one read of the merge mapping.
 */
data class DiarizedRead(
    val segments: List<Segment>,
    val speakers: List<Speaker>,
)
