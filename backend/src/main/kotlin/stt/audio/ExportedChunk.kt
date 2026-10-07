package stt.audio

import java.nio.file.Path

/** A planned chunk and the FLAC [file] (16-bit, 16 kHz mono) it was exported to. */
data class ExportedChunk(
    val planned: PlannedChunk,
    val file: Path,
)
