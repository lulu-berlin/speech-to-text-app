package stt.transcribe

import java.nio.file.Path

/**
 * Runs one chunk of a task through whisper-cli (ADR 0004) and returns its segments, offsets relative to [audio].
 * The seam the engine drives (roadmap item 11); [ChunkTranscriber] is the whisper-cli implementation.
 */
interface Transcriber {
    /**
     * Transcribes [audio] and returns its segments with offsets relative to it. [onProgress] reports the
     * chunk fraction parsed from whisper-cli's `-pp` output. Failures are [TranscriptionException]s.
     */
    suspend fun transcribe(
        settings: WhisperSettings,
        audio: Path,
        outputPrefix: Path,
        prompt: String?,
        onProgress: (Double) -> Unit = {},
    ): List<WhisperSegment>
}
