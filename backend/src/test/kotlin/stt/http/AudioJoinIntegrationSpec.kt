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
                    val target = joinTwoSineChunks(dataDir, runner)

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
                    val joined = joinTwoSineChunks(tempdir("stt-join-seek").toPath(), runner)

                    // What a browser seeks with (item 29, ADR 0045): the header's total sample count, and each
                    // frame's own position. 0.8 s × 16 kHz = 12 800 samples, by hand.
                    ffprobe(runner, joined, "stream=duration_ts").single().toLong() shouldBe 12_800L
                    val timestamps = ffprobe(runner, joined, "packet=pts").map { it.toLong() }
                    timestamps.zipWithNext().filter { (before, after) -> after <= before }.shouldBeEmpty()
                }
            }
        }
    })

/**
 * Stores two sine-wave chunk FLACs (0.3 s at 440 Hz, then 0.5 s at 880 Hz) for a new task in [dataDir] and joins
 * them with the real [FfmpegAudioJoiner]; returns the joined file.
 */
private suspend fun joinTwoSineChunks(
    dataDir: Path,
    runner: ProcessRunner,
): Path {
    val chunk0 = sineFlac(dataDir, "chunk-0", "440", "0.3")
    val chunk1 = sineFlac(dataDir, "chunk-1", "880", "0.5")
    val target = dataDir.resolve("joined.flac")
    Database.open(dataDir).use { db ->
        val workDirectories = TaskWorkDirectories(dataDir)
        val id =
            TaskRepository(db, workDirectories).createFromUpload(
                queuedTask("join.wav", createdAt = 1).copy(queuePosition = 0),
                "audio".toByteArray().inputStream(),
            )
        ChunkRepository(db).storePlan(
            id,
            listOf(
                ExportedChunk(PlannedChunk(0L, 300L), chunk0),
                ExportedChunk(PlannedChunk(300L, 800L), chunk1),
            ),
        )
        FfmpegAudioJoiner(runner, ChunkRepository(db), workDirectories).join(id, target)
    }
    return target
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
