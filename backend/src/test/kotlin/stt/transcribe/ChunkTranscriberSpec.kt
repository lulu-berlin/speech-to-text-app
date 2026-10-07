package stt.transcribe

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.config.Device
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.seconds

class ChunkTranscriberSpec :
    BehaviorSpec({
        tags(Integration)

        given("a whisper-cli that exits with code 2 after printing its error at the top of stderr") {
            `when`("a chunk is transcribed") {
                then("the chunk fails with the exit code and the full stderr") {
                    // whisper-cli 1.9.2 with a missing input file exits 2: the error comes first, then ~70 lines
                    // of usage (12 kept here), so a tail of stderr would not contain it.
                    val stderr =
                        listOf(
                            "load_backend: loaded CPU backend from /nix/store/whisper-cpp-1.9.2/lib/libggml-cpu-haswell.so",
                            "error: input file not found '/work/chunk-2.flac'",
                            "error: no input files specified",
                            "",
                            "usage: whisper-cli [options] file0 file1 ...",
                            "supported audio formats: flac, mp3, ogg, wav",
                            "",
                            "options:",
                            "  -h,        --help                 [default] show this help message and exit",
                            "             --version              show version information and exit",
                            "  -t N,      --threads N            [4      ] number of threads to use during computation",
                            "  -p N,      --processors N         [1      ] number of processors to use during computation",
                            "  -ot N,     --offset-t N           [0      ] time offset in milliseconds",
                            "  -on N,     --offset-n N           [0      ] segment index offset",
                            "  -d  N,     --duration N           [0      ] duration of audio to process in milliseconds",
                            "  -mc N,     --max-context N        [-1     ] maximum number of text context tokens to store",
                        )
                    val workDir = tempdir("stt-transcribe").toPath()
                    val whisperCli = fakeWhisperCli(workDir, stderr, exitCode = 2)
                    val transcriber = ChunkTranscriber(ProcessRunner(), whisperCli.toString())

                    val error =
                        shouldThrow<TranscriptionException> {
                            withTimeout(30.seconds) {
                                transcriber.transcribe(
                                    WhisperSettings(Path.of("/models/ggml-tiny.en.bin"), "en", threads = null, Device.Cpu),
                                    audio = workDir.resolve("chunk-2.flac"),
                                    outputPrefix = workDir.resolve("chunk-2"),
                                    prompt = null,
                                )
                            }
                        }

                    error.message.shouldContain("exit 2")
                    error.message.shouldContain(stderr.joinToString("\n"))
                }
            }
        }
    })

/**
 * An executable script standing in for whisper-cli: prints [stderr] to stderr, then exits with [exitCode].
 * The shebang names `sh` from PATH: neither `/bin/sh` nor `/usr/bin/env` exists in every environment (Nix).
 */
private fun fakeWhisperCli(
    dir: Path,
    stderr: List<String>,
    exitCode: Int,
): Path {
    val script = dir.resolve("whisper-cli")
    Files.writeString(script, "#!${shell()}\ncat >&2 <<'EOF'\n${stderr.joinToString("\n")}\nEOF\nexit $exitCode\n")
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
    return script
}

private fun shell(): Path =
    System
        .getenv("PATH")
        .split(":")
        .map { Path.of(it, "sh") }
        .first { Files.isExecutable(it) }
