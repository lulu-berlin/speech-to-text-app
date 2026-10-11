package stt.transcribe

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
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
                    val json = workDir.resolve("chunk-2.json")

                    // whisper-cli 1.9.2 on a file that is not audio: exit 0, no JSON, the reason only in stderr.
                    val unreadable =
                        listOf(
                            "[SWR @ 0x5e9c1d128000] Failed to set option",
                            "error: failed to allocate swr context",
                            "read_audio_data: failed to ffmpeg decode",
                            "error: failed to read audio file 'chunk-2.flac'",
                        )
                    val missing = transcribeFailing(fakeWhisperCli(workDir, unreadable, exitCode = 0), workDir)
                    missing.message.shouldContain("missing")
                    missing.message.shouldContain(json.toString())
                    missing.message.shouldContain(unreadable.joinToString("\n"))

                    val saving = listOf("output_json: saving output to '$json'")
                    val malformed = transcribeFailing(fakeWhisperCli(workDir, saving, exitCode = 0, writes = json to "{not json"), workDir)
                    malformed.message.shouldContain("malformed")
                    malformed.message.shouldContain(json.toString())
                    malformed.message.shouldContain(saving.joinToString("\n"))
                }
            }
        }

        given("a whisper-cli that writes legacy `segments` JSON whose entry lacks `start`, `end` or `text`") {
            `when`("a chunk is transcribed") {
                then("the chunk fails as malformed JSON naming the file, with the full stderr") {
                    val workDir = tempdir("stt-transcribe").toPath()
                    val json = workDir.resolve("chunk-2.json")
                    val saving = listOf("output_json: saving output to '$json'")
                    val lackingOneKey =
                        listOf(
                            """{"segments": [{"end": 7.94, "text": " And so my fellow Americans"}]}""",
                            """{"segments": [{"start": 0.0, "text": " And so my fellow Americans"}]}""",
                            """{"segments": [{"start": 0.0, "end": 7.94}]}""",
                        )

                    for (output in lackingOneKey) {
                        val whisperCli = fakeWhisperCli(workDir, saving, exitCode = 0, writes = json to output)
                        val error = transcribeFailing(whisperCli, workDir)
                        error.message.shouldContain("malformed")
                        error.message.shouldContain(json.toString())
                        error.message.shouldContain(saving.joinToString("\n"))
                    }
                }
            }
        }

        given("a whisper-cli that prints `no GPU found` and still transcribes, as 1.9.2 does on the CPU when no GPU is usable") {
            `when`("a chunk is transcribed with GPU 1 chosen, with the CPU chosen, and with auto") {
                then("only the GPU 1 run reports the GPU as unavailable; all three return the segment") {
                    val workDir = tempdir("stt-transcribe").toPath()
                    val json = workDir.resolve("chunk-2.json")
                    // Recorded from whisper-cli 1.9.2 with `--device 1` on a machine without a GPU: exit 0, JSON written.
                    val stderr =
                        listOf(
                            "whisper_init_with_params_no_state: use gpu    = 1",
                            "whisper_init_with_params_no_state: gpu_device = 1",
                            "whisper_backend_init_gpu: device 0: CPU (type: 0)",
                            "whisper_backend_init_gpu: no GPU found",
                            "output_json: saving output to '$json'",
                        )
                    val output = """{"segments": [{"start": 0.0, "end": 7.94, "text": " And so my fellow Americans"}]}"""
                    val whisperCli = fakeWhisperCli(workDir, stderr, exitCode = 0, writes = json to output)
                    val transcriber = ChunkTranscriber(ProcessRunner(), whisperCli.toString())

                    val reports =
                        listOf(Device.Gpu(1), Device.Cpu, Device.Auto).map { device ->
                            var unavailable = 0
                            val segments =
                                withTimeout(30.seconds) {
                                    transcriber.transcribe(
                                        TEST_SETTINGS.copy(device = device),
                                        workDir.resolve("chunk-2.flac"),
                                        workDir.resolve("chunk-2"),
                                        prompt = null,
                                        onGpuUnavailable = { unavailable++ },
                                    )
                                }
                            segments.map { it.text } shouldBe listOf(" And so my fellow Americans")
                            unavailable
                        }

                    reports shouldBe listOf(1, 0, 0)
                }
            }
        }
    })

/** Transcribes `chunk-2.flac` in [workDir] with [whisperCli] and returns the [TranscriptionException] it must fail with. */
private suspend fun transcribeFailing(
    whisperCli: Path,
    workDir: Path,
): TranscriptionException =
    shouldThrow<TranscriptionException> {
        withTimeout(30.seconds) {
            ChunkTranscriber(ProcessRunner(), whisperCli.toString())
                .transcribe(
                    TEST_SETTINGS,
                    workDir.resolve("chunk-2.flac"),
                    workDir.resolve("chunk-2"),
                    prompt = null,
                    onProgress = {},
                )
        }
    }
