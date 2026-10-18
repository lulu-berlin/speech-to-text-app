package stt.audio

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.process.ProcessRunner
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** `allowed-extensions.json`'s extensions (roadmap item 61), each with a committed fixture at `sample.<extension>`. */
private val UPLOAD_EXTENSIONS =
    listOf(
        "mp3",
        "wav",
        "flac",
        "ogg",
        "oga",
        "opus",
        "m4a",
        "aac",
        "wma",
        "aif",
        "aiff",
        "amr",
        "webm",
        "mp4",
        "m4v",
        "mov",
        "mkv",
        "mka",
        "3gp",
    )

class UploadFormatsSpec :
    BehaviorSpec({
        tags(Integration)

        given("one committed 0.5 s fixture for every extension the app accepts") {
            `when`("each is chunked with the real ffmpeg") {
                then("it decodes to the canonical 16 kHz mono s16 FLAC") {
                    val fixtures = fixtureDir()
                    UPLOAD_EXTENSIONS.forEach { extension ->
                        withClue("sample.$extension") {
                            val source = fixtures.resolve("sample.$extension")
                            source.toFile().shouldExist()
                            val workDir = tempdir("stt-upload-$extension").toPath()

                            val chunked =
                                withTimeout(60.seconds) { AudioChunker(ProcessRunner()).chunk(source, workDir) }

                            streamInfo(workDir.resolve("canonical.flac")).shouldBe(FLAC_16_BIT_16K_MONO)
                            // 0.5 s, plus the priming/trimming spread of the lossy decoders (0.46-0.54 s measured).
                            val durationMs = chunked.durationMs.toInt()
                            check(durationMs in 400..600) { "sample.$extension decoded to $durationMs ms, expected ~500 ms" }
                        }
                    }
                }
            }
        }
    })

/** The committed fixtures (`src/test/resources/upload-formats`, generate.sh beside them), from the test classpath. */
private fun fixtureDir(): Path =
    Path.of(
        checkNotNull(UploadFormatsSpec::class.java.getResource("/upload-formats")) {
            "upload-formats/ missing from the test classpath"
        }.toURI(),
    )
