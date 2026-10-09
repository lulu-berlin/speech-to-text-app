package stt.engine

import stt.db.StoredTask

/** Records the tasks whose DIARIZING phase it ran, without loading any model. */
internal class FakeDiarizer : Diarizer {
    /** The ids of the tasks it diarized, in order. */
    val diarized = mutableListOf<Long>()

    override suspend fun diarize(task: StoredTask) {
        diarized.add(task.id)
    }
}
