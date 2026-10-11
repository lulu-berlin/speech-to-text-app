package stt.transcribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.db.Chunk
import stt.db.SegmentRepository
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** One planned chunk end to end (ADR 0004): transcribe, persist atomically, then clean up its files. */
class ChunkTranscription(
    private val transcriber: Transcriber,
    private val segments: SegmentRepository,
) {
    /**
     * Transcribes [chunk] of the task from its [audio] file, stores its segments at absolute positions and marks
     * it `DONE` in one transaction, then deletes [audio] and whisper-cli's `<outputPrefix>.json`, so the work
     * directory holds at most one chunk's files. The deletes are best-effort: a failure leaves the chunk stored
     * `DONE` and is logged as a warning — the leftover file is removed with the work directory when the task
     * reaches a terminal status (item 11).
     */
    suspend fun transcribe(
        taskId: Long,
        chunk: Chunk,
        settings: WhisperSettings,
        audio: Path,
        outputPrefix: Path,
        prompt: String?,
        onProgress: (Double) -> Unit = {},
        onGpuUnavailable: suspend () -> Unit = {},
    ) {
        val whisperSegments = transcriber.transcribe(settings, audio, outputPrefix, prompt, onProgress, onGpuUnavailable)
        segments.completeChunk(taskId, chunk.idx, chunkSegments(chunk.idx, chunk.startMs, whisperSegments))
        withContext(Dispatchers.IO) {
            deleteBestEffort(audio)
            deleteBestEffort(jsonOutput(outputPrefix))
        }
    }

    /** Deletes [file], logging one warning on failure instead of failing the (already committed) chunk. */
    private fun deleteBestEffort(file: Path) {
        try {
            Files.delete(file)
        } catch (e: IOException) {
            logger.warn(
                "Could not delete work file {}; it is removed with the work directory at a terminal task status: {}",
                file,
                e.toString(),
            )
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ChunkTranscription::class.java)
    }
}
