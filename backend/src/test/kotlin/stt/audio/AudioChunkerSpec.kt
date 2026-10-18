package stt.audio

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class AudioChunkerSpec :
    BehaviorSpec({
        tags(Integration)

        given("an 11 min, 44.1 kHz tone with zeroed silences at 200–202 s, 289–291 s and 599–601 s") {
            `when`("it is chunked") {
                then(
                    "it is split at the expected silence midpoints into windows of the decoded clip",
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
                    chunked.chunks.shouldBe(
                        listOf(
                            PlannedChunk(startMs = 0, endMs = 290_000),
                            PlannedChunk(startMs = 290_000, endMs = 600_000),
                            PlannedChunk(startMs = 600_000, endMs = 660_000),
                        ),
                    )
                }
            }
        }

        given("a work directory left by an interrupted run: a stale canonical FLAC") {
            `when`("a 3 s, 44.1 kHz tone is chunked there again") {
                then("the old canonical FLAC is overwritten with the 16 kHz mono clip, stored row by row") {
                    val workDir = tempdir("stt-audio").toPath()
                    val source = workDir.resolve("source.wav")
                    generate(source, "sine=frequency=440:sample_rate=44100:duration=3")
                    Files.writeString(workDir.resolve("canonical.flac"), "stale")

                    val chunked = withTimeout(60.seconds) { AudioChunker(ProcessRunner()).chunk(source, workDir) }

                    // 3 s × 16 000 = 48 000 samples: 3 000 ms, one chunk (shorter than the target).
                    chunked.durationMs.shouldBe(3_000L)
                    chunked.chunks.shouldBe(listOf(PlannedChunk(startMs = 0, endMs = 3_000)))
                    streamInfo(workDir.resolve("canonical.flac")).shouldBe(FLAC_16_BIT_16K_MONO)
                    // The overwrite also reached the canonical FLAC, and what is stored is that file in the
                    // canonical layout: 48 000 / 4 000 = 12 frames of 4000 samples, four per one-second row.
                    chunked.canonical.rows
                        .map { it.frames.size }
                        .shouldBe(listOf(4, 4, 4))
                    storedBytes(chunked.canonical).shouldBe(Files.readAllBytes(workDir.resolve("canonical.flac")))
                }
            }
        }

        given("2.4 s plus 7 samples of 16 kHz pink noise: 38 407 samples, 9 whole 4000-sample frames and a 2407-sample tail") {
            `when`("it is chunked") {
                then(
                    "the stored header and rows are the canonical FLAC byte for byte, decode to the source's samples in order, " +
                        "and durationMs is the sample count / 16",
                ) {
                    val chunked = chunkNoise(tempdir("stt-audio").toPath(), 38_407)
                    // 38 407 / 16 = 2400.4 → 2 400 ms, the last whole millisecond (as the decoder derived it before).
                    chunked.durationMs.shouldBe(2_400L)
                }
            }
        }

        given("10 048 samples of 16 kHz pink noise: 2 × 4000 + 2048, so the last frame has FLAC's standard 2048-sample block size") {
            `when`("it is chunked") {
                then("the stored header and rows are the canonical FLAC byte for byte and decode to the source's samples in order") {
                    val chunked = chunkNoise(tempdir("stt-audio").toPath(), 10_048)
                    // Three frames in one row: 4000, 4000 and the 2048-sample tail; 10 048 / 16 = 628 ms.
                    chunked.canonical.rows
                        .map { it.frames.size }
                        .shouldBe(listOf(3))
                    chunked.durationMs.shouldBe(628L)
                }
            }
        }

        given("a silencedetect run whose input file is missing") {
            `when`("it is run") {
                then("it fails chunking with ffmpeg's error message") {
                    val workDir = tempdir("stt-audio").toPath()
                    val source = workDir.resolve("missing.wav")
                    val chunker = AudioChunker(ProcessRunner())

                    val detectError =
                        shouldThrow<AudioDecodeException> { withTimeout(30.seconds) { chunker.detectSilences(source, 1_000) } }

                    detectError.message.shouldContain("ffmpeg failed to detect silences in $source")
                    detectError.message.shouldContain("No such file or directory") // ffmpeg's stderr
                }
            }
        }
    })

/** What the database stores for [rows]: the header row followed by every second's frames, in order. */
private fun storedBytes(rows: FlacRows): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(rows.header)
    rows.rows.forEach { out.write(it.joinToBytes()) }
    return out.toByteArray()
}

/**
 * Chunks [samples] of 16 kHz pink noise in [workDir] and checks what every exact-storage scenario needs: the source
 * really has [samples] samples, the stored header and rows are the canonical FLAC byte for byte, and they decode to
 * the source's own samples in order (the source is already 16 kHz mono s16, so the canonical encode is lossless).
 * Returns the chunker's result for the scenario's own checks.
 */
private suspend fun chunkNoise(
    workDir: Path,
    samples: Int,
): ChunkedAudio {
    val source = workDir.resolve("source.wav")
    generateNoise(source, samples, silences = emptyList())
    val sourceSamples = toS16(source, workDir)
    sourceSamples.size.shouldBe(samples * 2)

    val chunked = withTimeout(60.seconds) { AudioChunker(ProcessRunner()).chunk(source, workDir) }

    val stored = storedBytes(chunked.canonical)
    stored.shouldBe(Files.readAllBytes(workDir.resolve("canonical.flac")))
    val storedFile = workDir.resolve("stored.flac")
    Files.write(storedFile, stored)
    toS16(storedFile, workDir).shouldBe(sourceSamples)
    return chunked
}
