package stt.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
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

    /** The work directory of every call, as the call starts, so a test can wait for the engine to be inside one. */
    val entered = Channel<Path>(Channel.UNLIMITED)

    /** When set, every call waits for it before returning; a test clears it to let later calls run through. */
    var hold: CompletableDeferred<Unit>? = null

    /**
     * The task whose plan also names a second chunk whose FLAC was never written, so storing the plan fails
     * part-way; null for every task. Lets a test see that a plan is stored all-or-nothing.
     */
    var unwritablePlanFor: Long? = null

    val probe = ConcurrencyProbe()

    override suspend fun chunk(
        source: Path,
        workDir: Path,
    ): ChunkedAudio =
        probe.around {
            workDirs.add(workDir)
            entered.send(workDir)
            hold?.await()
            val file = workDir.resolve("chunk-0.flac")
            Files.writeString(file, "fake flac")
            val chunks =
                if (taskIdOf(workDir) == unwritablePlanFor) {
                    listOf(
                        ExportedChunk(PlannedChunk(startMs = 0, endMs = 1_000), file),
                        ExportedChunk(PlannedChunk(startMs = 1_000, endMs = 2_000), workDir.resolve("chunk-1.flac")),
                    )
                } else {
                    listOf(ExportedChunk(PlannedChunk(startMs = 0, endMs = 1_000), file))
                }
            ChunkedAudio(durationMs = chunks.last().planned.endMs, chunks = chunks)
        }
}
