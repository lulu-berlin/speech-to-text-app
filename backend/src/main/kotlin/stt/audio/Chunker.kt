package stt.audio

import java.nio.file.Path

/**
 * Decodes a source, plans its chunks at silences, and exports each chunk as FLAC (ADR 0004, ADR 0024).
 * The seam the engine drives (roadmap item 11); [AudioChunker] is the ffmpeg implementation.
 */
interface Chunker {
    /** Chunks [source] into FLAC files in [workDir] and returns the decoded duration and the exported chunks. */
    suspend fun chunk(
        source: Path,
        workDir: Path,
    ): ChunkedAudio
}
