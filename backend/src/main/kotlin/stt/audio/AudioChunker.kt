package stt.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stt.db.CanonicalAudio
import stt.process.ProcessRunner
import java.nio.channels.FileChannel
import java.nio.file.Path

private const val TARGET_MS = 5 * 60 * 1000L
private const val CAP_MS = 6 * 60 * 1000L
private const val SILENCE_NOISE = "-30dB"
private const val SILENCE_MIN_SECONDS = "0.5"

/**
 * A task's canonical clip (item 44): the scratch file it was encoded to and where its header and frames lie in it.
 * The rows are read from the file as they are stored (item 71); the file goes with the chunker's scratch directory,
 * which the engine removes only after the clip is stored.
 */
internal class CanonicalClip(
    private val layout: FlacLayout,
    private val file: Path,
) : CanonicalRows {
    override val header: ByteArray get() = layout.header

    /** The clip's sample count, from STREAMINFO. */
    val totalSamples: Long get() = layout.totalSamples

    override suspend fun <T> useRows(block: suspend (Sequence<CanonicalAudio>) -> T): T {
        val channel = withContext(Dispatchers.IO) { FileChannel.open(file) }
        try {
            return block(layout.rows(FileFlacBytes(channel)))
        } finally {
            withContext(Dispatchers.IO) { channel.close() }
        }
    }
}

/**
 * The canonical clip's output options (item 44): 16 kHz mono, 16-bit samples, FLAC, fixed 4000-sample
 * frames (250 ms) — the layout `FlacSplitter` requires and `audio_second`'s one-second rows hold. Shared
 * with the upgrade's backfill ([stt.db.migration.V10__BackfillCanonicalClip]), which re-encodes stored
 * chunk FLACs into the same form.
 */
internal val CANONICAL_FLAC_ARGS =
    listOf("-vn", "-ar", SAMPLE_RATE.toString(), "-ac", "1", "-sample_fmt", "s16", "-c:a", "flac", "-frame_size", "4000")

/** Encodes [source] to the canonical 16 kHz mono FLAC (item 44): every frame exactly 4000 samples (250 ms). */
internal suspend fun encodeCanonical(
    runner: ProcessRunner,
    source: Path,
    target: Path,
) {
    val result =
        runner.run(
            FFMPEG +
                listOf("-y", "-loglevel", "error", "-i", source.toString()) +
                CANONICAL_FLAC_ARGS +
                listOf(target.toString()),
        )
    result.expectSuccess("encode $source to canonical FLAC")
}

/** Encodes a source to the canonical clip and plans its chunks at silences, as windows into that clip (item 44). */
class AudioChunker(
    private val runner: ProcessRunner,
) : Chunker {
    /** Encodes and splits the canonical clip in [scratchDir], then plans [source]'s chunk windows. */
    override suspend fun chunk(
        source: Path,
        scratchDir: Path,
    ): ChunkedAudio {
        val canonical = canonicalize(source, scratchDir)
        // The clip's duration, to the last whole millisecond: its sample count / 16 per ms.
        val durationMs = canonical.totalSamples / SAMPLES_PER_MS
        val silences = detectSilences(source, durationMs)
        val chunks = planChunks(durationMs, silences, TARGET_MS, CAP_MS)
        return ChunkedAudio(durationMs, chunks, canonical)
    }

    /** Encodes [source] to the canonical FLAC at [scratchDir]/canonical.flac and finds its header and frames in it. */
    internal suspend fun canonicalize(
        source: Path,
        scratchDir: Path,
    ): CanonicalClip {
        val target = scratchDir.resolve("canonical.flac")
        encodeCanonical(runner, source, target)
        val layout = withContext(Dispatchers.IO) { FileChannel.open(target).use { FlacSplitter.layout(FileFlacBytes(it)) } }
        return CanonicalClip(layout, target)
    }

    /** Runs `silencedetect` over [source]; internal as a test seam (a failing run cannot be provoked through [chunk]). */
    internal suspend fun detectSilences(
        source: Path,
        durationMs: Long,
    ): List<Silence> {
        val result =
            runner.run(
                FFMPEG +
                    listOf(
                        "-i",
                        source.toString(),
                        "-vn",
                        "-af",
                        "silencedetect=noise=$SILENCE_NOISE:d=$SILENCE_MIN_SECONDS",
                        "-f",
                        "null",
                        "-",
                    ),
            )
        result.expectSuccess("detect silences in $source")
        return parseSilences(result.stderr, durationMs)
    }
}
