package stt.audio

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import stt.Integration
import stt.process.ProcessRunner
import java.nio.file.Files

/**
 * Item 44, test-list item "(discovered) A truncated FLAC that ends in a frame-sync pair is refused as not canonical
 * — a failed task with a clear error — not a crash".
 */
class FlacSplitterSpec :
    BehaviorSpec({
        tags(Integration)

        given("a canonical FLAC cut off right after the sync pair that starts its third frame") {
            `when`("it is split") {
                then("it is refused as not canonical, naming the frame, instead of reading past the end") {
                    val dir = tempdir("stt-splitter").toPath()
                    val source = dir.resolve("source.wav")
                    generateNoise(source, 3 * 16_000, silences = emptyList())
                    val encoded = dir.resolve("canonical.flac")
                    encodeCanonical(ProcessRunner(), source, encoded)
                    val whole = FlacSplitter.split(Files.readAllBytes(encoded))
                    // The truncated stream: the header, frames 0 and 1 whole, then only frame 2's two sync bytes.
                    val truncated = whole.header + whole.frames[0] + whole.frames[1] + whole.frames[2].copyOf(2)
                    (truncated[truncated.size - 2].toInt() and 0xFF) shouldBe 0xFF // the fixture really ends in a sync pair

                    val refused = shouldThrow<FlacSplitter.NotCanonical> { FlacSplitter.split(truncated) }
                    refused.message.orEmpty() shouldContain "frame 2"
                }
            }
        }

        given("a canonical FLAC of 48 000 samples (12 whole frames) whose STREAMINFO declares 52 000") {
            `when`("it is split") {
                then("it is refused as not canonical, naming both counts, though every frame is intact") {
                    val dir = tempdir("stt-splitter").toPath()
                    val source = dir.resolve("source.wav")
                    generateNoise(source, 48_000, silences = emptyList())
                    val encoded = dir.resolve("canonical.flac")
                    encodeCanonical(ProcessRunner(), source, encoded)
                    val flac = Files.readAllBytes(encoded)
                    FlacSplitter.split(flac).totalSamples shouldBe 48_000L // the encode itself is canonical

                    // STREAMINFO's 36-bit total sits in the low nibble of byte 21 and bytes 22–25; 52 000 fits in the last two.
                    val misdeclared = flac.copyOf()
                    misdeclared[21] = (misdeclared[21].toInt() and 0xF0).toByte()
                    misdeclared[22] = 0
                    misdeclared[23] = 0
                    misdeclared[24] = (52_000 shr 8).toByte()
                    misdeclared[25] = (52_000 and 0xFF).toByte()

                    val refused = shouldThrow<FlacSplitter.NotCanonical> { FlacSplitter.split(misdeclared) }
                    refused.message.orEmpty() shouldContain "48000"
                    refused.message.orEmpty() shouldContain "52000"
                }
            }
        }
    })
