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

    /** The first call to wait on [hold]; earlier calls run through. */
    var holdFrom: Int = 1

    /** How many chunks every call plans. */
    var chunkCount: Int = 1

    /**
     * The task whose plan also names one chunk more than [chunkCount], whose FLAC was never written, so
     * storing the plan fails part-way; null for every task. Lets a test see that a plan is stored
     * all-or-nothing.
     */
    var unwritablePlanFor: Long? = null

    val probe = ConcurrencyProbe()

    override suspend fun chunk(
        source: Path,
        workDir: Path,
    ): ChunkedAudio =
        probe.around {
            val task = taskIdOf(workDir)
            workDirs.add(workDir)
            val planned =
                (0 until chunkCount + if (task == unwritablePlanFor) 1 else 0).map { idx ->
                    PlannedChunk(startMs = idx * 1_000L, endMs = (idx + 1) * 1_000L)
                }
            val chunks =
                planned.mapIndexed { idx, chunk ->
                    val file = workDir.resolve("chunk-$idx.flac")
                    if (task == unwritablePlanFor && idx == planned.lastIndex) {
                        // Named by the plan, but never written, so storing the plan fails part-way.
                        ExportedChunk(chunk, file)
                    } else {
                        ExportedChunk(chunk, Files.writeString(file, "fake flac"))
                    }
                }
            entered.send(workDir)
            if (workDirs.size >= holdFrom) hold?.await()
            ChunkedAudio(durationMs = planned.last().endMs, chunks = chunks)
        }
}
