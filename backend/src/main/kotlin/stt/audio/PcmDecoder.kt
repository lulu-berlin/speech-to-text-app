package stt.audio

import java.nio.file.Path

/**
 * Decodes an audio file into 16 kHz mono float32 PCM as a file; [AudioDecoder] is the ffmpeg
 * implementation. The engine's DIARIZING phase uses it to rebuild a task's whole-file PCM from its stored
 * chunk FLACs (ADR 0024). A seam, so engine tests stand in a deterministic fake.
 */
interface PcmDecoder {
    /** Decodes [source] into the PCM file [target], returning the decoded duration in milliseconds. */
    suspend fun decode(
        source: Path,
        target: Path,
    ): Long
}
