package stt.audio

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** ffprobe's view of a chunk FLAC as ADR 0024 requires it: FLAC, 16-bit, 16 kHz, mono. */
private val FLAC_16_BIT_16K_MONO = mapOf("codec_name" to "flac", "sample_fmt" to "s16", "sample_rate" to "16000", "channels" to "1")

class AudioChunkerSpec :
    BehaviorSpec({
        tags(Integration)

        given("an 11 min, 44.1 kHz tone with zeroed silences at 200–202 s, 289–291 s and 599–601 s") {
            `when`("it is chunked") {
                then(
                    "it is split at the expected silence midpoints and the exported chunk FLACs (16-bit, 16 kHz mono) have the planned lengths",
                ) {
                    val workDir = tempdir("stt-audio").toPath()
                    val source = workDir.resolve("source.wav")
                    val silent = "between(t,200,202)+between(t,289,291)+between(t,599,601)"
                    generate(source, "aevalsrc='if($silent,0,0.5*sin(2*PI*440*t))':s=44100:d=660")

                    val chunked = withTimeout(120.seconds) { AudioChunker(ProcessRunner()).chunk(source, workDir) }

                    // Chunk 1 from 0: the 289–291 s midpoint 290 s is nearest to 300 s (201 s is farther).
                    // Chunk 2 from 290 s: the 599–601 s midpoint 600 s is nearest to 590 s.
                    // Chunk 3 from 600 s: the remaining 60 s is shorter than the target and at least 30 s.
                    chunked.durationMs.shouldBe(660_000L)
                    chunked.chunks.map { it.planned }.shouldBe(
                        listOf(
                            PlannedChunk(startMs = 0, endMs = 290_000),
                            PlannedChunk(startMs = 290_000, endMs = 600_000),
                            PlannedChunk(startMs = 600_000, endMs = 660_000),
                        ),
                    )
                    for (chunk in chunked.chunks) {
                        streamInfo(chunk.file).shouldBe(FLAC_16_BIT_16K_MONO)
                    }
                    // Planned length × 16 samples per ms: 290 s, 310 s and 60 s at 16 000 samples/s.
                    chunked.chunks.map { sampleCount(it.file, workDir) }.shouldBe(listOf(4_640_000L, 4_960_000L, 960_000L))
                }
            }
        }

        given("a work directory left by an interrupted run: a PCM cut off after 1 s and a truncated chunk-0.flac") {
            `when`("a 3 s, 44.1 kHz tone is chunked there again") {
                then("the old PCM and chunk files are overwritten") {
                    val workDir = tempdir("stt-audio").toPath()
                    val source = workDir.resolve("source.wav")
                    generate(source, "sine=frequency=440:sample_rate=44100:duration=3")
                    Files.write(workDir.resolve("audio.f32"), ByteArray(64_000)) // 1 s × 16 000 samples × 4 bytes
                    Files.writeString(workDir.resolve("chunk-0.flac"), "truncated")

                    val chunked = withTimeout(60.seconds) { AudioChunker(ProcessRunner()).chunk(source, workDir) }

                    // 3 s × 16 000 = 48 000 samples: 3 000 ms, 192 000 bytes of float32, one chunk (shorter than the target).
                    chunked.durationMs.shouldBe(3_000L)
                    Files.size(workDir.resolve("audio.f32")).shouldBe(192_000L)
                    chunked.chunks.map { it.planned }.shouldBe(listOf(PlannedChunk(startMs = 0, endMs = 3_000)))
                    val chunk = chunked.chunks.single()
                    streamInfo(chunk.file).shouldBe(FLAC_16_BIT_16K_MONO)
                    sampleCount(chunk.file, workDir).shouldBe(48_000L)
                }
            }
        }
    })

/** The audio stream's codec, sample format, sample rate and channel count as reported by ffprobe. */
private suspend fun streamInfo(file: Path): Map<String, String> {
    val command =
        listOf(
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "stream=codec_name,sample_fmt,sample_rate,channels",
            "-of",
            "default=noprint_wrappers=1",
            file.toString(),
        )
    val result = withTimeout(30.seconds) { ProcessRunner().run(command) }
    check(result.exitCode == 0) { "ffprobe failed: ${result.stderr}" }
    return result.stdout.associate { line -> line.substringBefore('=') to line.substringAfter('=') }
}

/** The number of samples in [file], counted by decoding it with ffmpeg to raw 16-bit mono. */
private suspend fun sampleCount(
    file: Path,
    scratchDir: Path,
): Long {
    val raw = scratchDir.resolve("${file.fileName}.s16")
    val command =
        listOf("ffmpeg", "-hide_banner", "-nostats", "-loglevel", "error", "-i", file.toString(), "-f", "s16le", "-ac", "1", raw.toString())
    val result = withTimeout(30.seconds) { ProcessRunner().run(command) }
    check(result.exitCode == 0) { "ffmpeg failed: ${result.stderr}" }
    return Files.size(raw) / 2
}
