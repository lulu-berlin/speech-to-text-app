package stt.engine

import stt.audio.ChunkedAudio
import stt.audio.Chunker
import stt.audio.ExportedChunk
import stt.audio.PlannedChunk
import java.nio.file.Files
import java.nio.file.Path

/** A [Chunker] that exports one 1-second FLAC chunk without reading the source; records every call. */
internal class FakeChunker : Chunker {
    /** The work directory of every call, in call order. */
    val workDirs = mutableListOf<Path>()

    val probe = ConcurrencyProbe()

    override suspend fun chunk(
        source: Path,
        workDir: Path,
    ): ChunkedAudio =
        probe.around {
            workDirs.add(workDir)
            val file = workDir.resolve("chunk-0.flac")
            Files.writeString(file, "fake flac")
            ChunkedAudio(durationMs = 1_000L, chunks = listOf(ExportedChunk(PlannedChunk(startMs = 0, endMs = 1_000), file)))
        }
}
