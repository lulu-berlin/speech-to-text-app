package stt.audio

import java.nio.file.Path

/**
 * Encodes a source to the canonical clip and plans its chunks at silences (ADR 0004, item 44). The seam the engine
 * drives (roadmap item 11); [AudioChunker] is the ffmpeg implementation.
 */
interface Chunker {
    /** Chunks [source], using [scratchDir] for its transient files; returns the duration, the windows and the clip. */
    suspend fun chunk(
        source: Path,
        scratchDir: Path,
    ): ChunkedAudio
}
