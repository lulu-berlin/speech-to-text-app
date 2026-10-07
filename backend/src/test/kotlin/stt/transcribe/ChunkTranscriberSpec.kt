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

        given("a whisper-cli that exits 0 but writes no JSON, and one that writes malformed JSON") {
            `when`("a chunk is transcribed") {
                then("the chunk fails with a clear message naming the JSON file, with the full stderr") {
                    val workDir = tempdir("stt-transcribe").toPath()
                    val prefix = workDir.resolve("chunk-2")
                    val json = workDir.resolve("chunk-2.json")

                    suspend fun transcribe(whisperCli: Path) =
                        shouldThrow<TranscriptionException> {
                            withTimeout(30.seconds) {
                                ChunkTranscriber(ProcessRunner(), whisperCli.toString())
                                    .transcribe(TEST_SETTINGS, workDir.resolve("chunk-2.flac"), prefix, prompt = null)
                            }
                        }

                    // whisper-cli 1.9.2 on a file that is not audio: exit 0, no JSON, the reason only in stderr.
                    val unreadable =
                        listOf(
                            "[SWR @ 0x5e9c1d128000] Failed to set option",
                            "error: failed to allocate swr context",
                            "read_audio_data: failed to ffmpeg decode",
                            "error: failed to read audio file 'chunk-2.flac'",
                        )
                    val missing = transcribe(fakeWhisperCli(workDir, unreadable, exitCode = 0))
                    missing.message.shouldContain("missing")
                    missing.message.shouldContain(json.toString())
                    missing.message.shouldContain(unreadable.joinToString("\n"))

                    val saving = listOf("output_json: saving output to '$json'")
                    val malformed = transcribe(fakeWhisperCli(workDir, saving, exitCode = 0, writes = json to "{not json"))
                    malformed.message.shouldContain("malformed")
                    malformed.message.shouldContain(json.toString())
                    malformed.message.shouldContain(saving.joinToString("\n"))
                }
            }
        }
    })

private val TEST_SETTINGS = WhisperSettings(Path.of("/models/ggml-tiny.en.bin"), "en", threads = null, Device.Cpu)

/**
 * An executable script standing in for whisper-cli: prints [stderr] to stderr, writes [writes] (file to content)
 * if given, then exits with [exitCode].
 * The shebang names `sh` from PATH: neither `/bin/sh` nor `/usr/bin/env` exists in every environment (Nix).
 */
private fun fakeWhisperCli(
    dir: Path,
    stderr: List<String>,
    exitCode: Int,
    writes: Pair<Path, String>? = null,
): Path {
    val script = dir.resolve("whisper-cli")
    val write = writes?.let { (file, content) -> "cat > '$file' <<'EOF'\n$content\nEOF\n" } ?: ""
    Files.writeString(script, "#!${shell()}\ncat >&2 <<'EOF'\n${stderr.joinToString("\n")}\nEOF\n${write}exit $exitCode\n")
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
    return script
}

private fun shell(): Path =
    System
        .getenv("PATH")
        .split(":")
        .map { Path.of(it, "sh") }
        .first { Files.isExecutable(it) }
