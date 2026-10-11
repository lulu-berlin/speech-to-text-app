package stt.http

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import stt.Integration
import stt.audio.ExportedChunk
import stt.audio.FfmpegAudioJoiner
import stt.audio.PlannedChunk
import stt.audio.expectSuccess
import stt.audio.sineFlac
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.db.queuedTask
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The real join, with real ffmpeg (ADR 0021's Integration tag): two sine-wave FLACs stored as the task's chunk
 * audio are joined with the concat demuxer, re-encoded to FLAC (ADR 0045), into one seekable FLAC whose duration
 * is the sum of the chunks.
 */
class AudioJoinIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a task whose chunks are two different sine-wave FLACs") {
            `when`("the real FfmpegAudioJoiner joins them") {
                then("one FLAC of the combined duration is produced") {
                    val runner = ProcessRunner()
                    val dataDir = tempdir("stt-join").toPath()
                    val target = joinTwoSineChunks(dataDir)

                    target.exists().shouldBeTrue()
                    String(Files.readAllBytes(target), 0, 4, Charsets.UTF_8) shouldBe "fLaC"

                    // Count the decoded samples, independent of what the header declares (the second scenario
                    // checks the header itself).
                    val decoded = dataDir.resolve("decoded.raw")
                    runner
                        .run(
                            listOf(
                                "ffmpeg",
                                "-hide_banner",
                                "-loglevel",
                                "error",
                                "-y",
                                "-i",
                                target.toString(),
                                "-f",
                                "s16le",
                                decoded.toString(),
                            ),
                        ).expectSuccess("decode the joined file")
                    // 0.8 s × 16 kHz × 2 bytes per 16-bit sample, by hand.
                    Files.size(decoded) shouldBe 25_600L
                }
            }
        }

        given("two chunk FLACs joined by the real FfmpegAudioJoiner") {
            `when`("a player reads the joined file's header and its frames' timestamps") {
                then("the header declares the full 0.8 s and the timestamps keep increasing across the chunk boundary") {
                    val runner = ProcessRunner()
                    val joined = joinTwoSineChunks(tempdir("stt-join-seek").toPath())

                    // What a browser seeks with (item 29, ADR 0045): the header's total sample count, and each
                    // frame's own position. 0.8 s × 16 kHz = 12 800 samples, by hand.
                    ffprobe(runner, joined, "stream=duration_ts").single().toLong() shouldBe 12_800L
                    val timestamps = ffprobe(runner, joined, "packet=pts").map { it.toLong() }
                    timestamps.zipWithNext().filter { (before, after) -> after <= before }.shouldBeEmpty()
                }
            }
        }

        given("a task whose first chunk ends early — its file declares more samples than it contains") {
            `when`("the real FfmpegAudioJoiner joins it with a following chunk") {
                then("the joined header matches the decodable content, and the tail chunk's audio is all still there") {
                    val runner = ProcessRunner()
                    val dataDir = tempdir("stt-join-trunc").toPath()
                    val target = dataDir.resolve("joined-trunc.flac")
                    // Chunk 0 declares 1 s (16 000 samples) but is cut to 70% of its bytes, so only its first
                    // frames are decodable (a truncated tail: how a stored chunk could end early). Chunk 1 is
                    // a full 0.5 s sine — 8 000 samples at 16 kHz, by hand.
                    val truncated =
                        dataDir.resolve("chunk-0.flac").also { file ->
                            val full = sineFlac(dataDir, "chunk-0-full", "440", "1.0")
                            val bytes = Files.readAllBytes(full)
                            Files.write(file, bytes.copyOf(bytes.size * 7 / 10))
                        }
                    val firstSamples = decodeSamples(runner, dataDir.resolve("first.raw"), truncated)
                    val tail = sineFlac(dataDir, "chunk-1", "880", "0.5")
                    joinStoredChunks(
                        dataDir,
                        target,
                        listOf(
                            PlannedChunk(0L, 500L) to truncated,
                            PlannedChunk(500L, 1_000L) to tail,
                        ),
                    )

                    // The tail chunk's audio is all still there: the joined content is the decodable part of
                    // the truncated chunk plus the full 0.5 s that followed it.
                    val decoded = decodeSamples(runner, dataDir.resolve("joined.raw"), target)
                    decoded shouldBe firstSamples + 8_000L
                    // The header a player reads must agree with what decoding actually yields — nothing
                    // declared beyond the frames, which is what a "waits for audio that never comes" stall
                    // needs. (The pre-ADR-0045 `-c copy` join kept the first chunk's declared length instead.)
                    ffprobe(runner, target, "stream=duration_ts").single().toLong() shouldBe decoded
                }
            }
        }
    })

/**
 * Stores two sine-wave chunk FLACs (0.3 s at 440 Hz, then 0.5 s at 880 Hz) for a new task in [dataDir] and joins
 * them with the real [FfmpegAudioJoiner] (which runs its own ProcessRunner per join, item 37); returns the joined file.
 */
private suspend fun joinTwoSineChunks(dataDir: Path): Path {
    val target = dataDir.resolve("joined.flac")
    joinStoredChunks(
        dataDir,
        target,
        listOf(
            PlannedChunk(0L, 300L) to sineFlac(dataDir, "chunk-0", "440", "0.3"),
            PlannedChunk(300L, 800L) to sineFlac(dataDir, "chunk-1", "880", "0.5"),
        ),
    )
    return target
}

/**
 * Stores [chunks] (each a planned range with its FLAC file) for a new task in [dataDir] and joins them with the
 * real [FfmpegAudioJoiner] into [target].
 */
private suspend fun joinStoredChunks(
    dataDir: Path,
    target: Path,
    chunks: List<Pair<PlannedChunk, Path>>,
) {
    Database.open(dataDir).use { db ->
        val workDirectories = TaskWorkDirectories(dataDir)
        val id =
            TaskRepository(db, workDirectories).createFromUpload(
                queuedTask("join.wav", createdAt = 1).copy(queuePosition = 0),
                "audio".toByteArray().inputStream(),
            )
        ChunkRepository(db).storePlan(
            id,
            chunks.map { (planned, file) -> ExportedChunk(planned, file) },
        )
        FfmpegAudioJoiner(ChunkRepository(db), workDirectories).join(id, target)
    }
}

/**
 * Decodes [file] to raw s16le and returns its sample count — what a player really gets out of the file,
 * independent of what any header declares (the first scenario uses the same decode to check the join).
 */
private suspend fun decodeSamples(
    runner: ProcessRunner,
    raw: Path,
    file: Path,
): Long {
    val result =
        runner.run(
            listOf(
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                file.toString(),
                "-f",
                "s16le",
                raw.toString(),
            ),
        )
    result.expectSuccess("decode $file")
    return Files.size(raw) / 2
}

/** One ffprobe `-show_entries` query on [file]'s audio stream: one value per line. */
private suspend fun ffprobe(
    runner: ProcessRunner,
    file: Path,
    entries: String,
): List<String> {
    val result =
        runner.run(
            listOf("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", entries, "-of", "csv=p=0", file.toString()),
        )
    check(result.exitCode == 0) { "ffprobe failed: ${result.stderr}" }
    return result.stdout.filter { it.isNotBlank() }
}
