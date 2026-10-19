package stt.audio

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class FlacStreamInfoSpec :
    BehaviorSpec({
        given("the committed 0.5 s 44.1 kHz mono sample.flac") {
            then("its STREAMINFO and first frame are read from the file, without ffprobe") {
                val file =
                    Path.of(
                        checkNotNull(FlacStreamInfoSpec::class.java.getResource("/upload-formats/sample.flac")) {
                            "sample.flac missing from the test classpath"
                        }.toURI(),
                    )

                val info = flacStreamInfo(file)

                info.sampleRate shouldBe 44100
                info.channels shouldBe 1
                info.bitsPerSample shouldBe 16
                info.totalSamples shouldBe 22050L
                val bytes = Files.readAllBytes(file)
                bytes[info.audioStart] shouldBe 0xFF.toByte()
                (bytes[info.audioStart + 1].toInt() and 0xFE) shouldBe 0xF8
            }
        }
    })
