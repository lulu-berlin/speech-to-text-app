package stt.transcribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.db.Chunk
import stt.db.SegmentRepository
import java.nio.file.Files
import java.nio.file.Path

/** One planned chunk end to end (ADR 0004): transcribe, persist atomically, then clean up its files. */
class ChunkTranscription(
    private val transcriber: ChunkTranscriber,
    private val segments: SegmentRepository,
) {
    /**
     * Transcribes [chunk] of the task from its [audio] file, stores its segments at absolute positions and marks
     * it `DONE` in one transaction, then deletes [audio] and whisper-cli's `<outputPrefix>.json`, so the work
     * directory holds at most one chunk's files.
     */
    suspend fun transcribe(
        taskId: Long,
        chunk: Chunk,
        settings: WhisperSettings,
        audio: Path,
        outputPrefix: Path,
        prompt: String?,
    ) {
        val whisperSegments = transcriber.transcribe(settings, audio, outputPrefix, prompt)
        segments.completeChunk(taskId, chunk.idx, chunkSegments(chunk.idx, chunk.startMs, whisperSegments))
        withContext(Dispatchers.IO) {
            Files.delete(audio)
            Files.delete(Path.of("$outputPrefix.json"))
        }
    }
}
