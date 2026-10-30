package stt.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import stt.audio.AudioDecodeException
import stt.audio.CanonicalRows
import stt.audio.ChunkedAudio
import stt.audio.Chunker
import stt.audio.FlacRow
import stt.audio.FlacRows
import stt.audio.PlannedChunk
import stt.audio.fakeCanonicalHeader
import stt.audio.toCanonicalRows
import stt.db.CanonicalAudio
import java.nio.file.Files
import java.nio.file.Path

/** A [Chunker] that plans one-second windows into a small stand-in clip without reading the source; records every call. */
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

    /** The canonical clip every call plans (item 44): a header naming [CLIP_SAMPLES] samples and one one-second row. */
    var canonical: FlacRows =
        FlacRows(
            header = fakeCanonicalHeader(CLIP_SAMPLES),
            rows = listOf(FlacRow(second = 0, frames = listOf("fake-frame".toByteArray()))),
        )

    /**
     * The task whose result cannot be stored: its clip names second 0 twice, so storing it breaks part-way on
     * `audio_second`'s key; null for every task. Lets a test see that a chunking result is stored all-or-nothing.
     */
    var unwritablePlanFor: Long? = null

    /** The task whose decode fails after its scratch file is written, as a real decode breaking part-way would. */
    var decodeFailsFor: Long? = null

    val probe = ConcurrencyProbe()

    override suspend fun chunk(
        source: Path,
        workDir: Path,
    ): ChunkedAudio =
        probe.around {
            val task = taskIdOf(workDir)
            workDirs.add(workDir)
            val planned =
                (0 until chunkCount).map { idx ->
                    PlannedChunk(startMs = idx * 1_000L, endMs = (idx + 1) * 1_000L)
                }
            // The real chunker's canonical encode lands in its scratch directory first.
            Files.writeString(workDir.resolve("canonical.flac"), "fake canonical encode")
            entered.send(workDir)
            if (workDirs.size >= holdFrom) hold?.await()
            if (task == decodeFailsFor) throw AudioDecodeException("fake decode failure of task $task")
            val clip = if (task == unwritablePlanFor) canonical.copy(rows = canonical.rows + canonical.rows.first()) else canonical
            ChunkedAudio(durationMs = planned.last().endMs, chunks = planned, canonical = InMemoryClip(clip))
        }
}

/** [clip] as the store reads a clip: its rows one at a time. */
private class InMemoryClip(
    private val clip: FlacRows,
) : CanonicalRows {
    override val header: ByteArray get() = clip.header

    override suspend fun <T> useRows(block: suspend (Sequence<CanonicalAudio>) -> T): T = block(clip.toCanonicalRows().asSequence())
}

/** The stand-in clip's length: one second, the one one-second row [FakeChunker] plans. */
private const val CLIP_SAMPLES = 16_000
