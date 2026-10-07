package stt.transcribe

import stt.db.SegmentRepository

/** The `--prompt` for chunk [chunkIdx] when a task resumes, rebuilt from the previous chunk's stored segments. */
suspend fun resumePrompt(
    segments: SegmentRepository,
    taskId: Long,
    chunkIdx: Int,
): String? {
    if (chunkIdx == 0) return null
    return promptTail(segments.findByTask(taskId).filter { it.chunkIdx == chunkIdx - 1 })
}
