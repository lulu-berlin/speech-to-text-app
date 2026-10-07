package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ParseProgressSpec :
    BehaviorSpec({
        given("whisper-cli -pp progress lines and unrelated stderr lines") {
            `when`("each line is parsed") {
                then("a progress line yields the chunk fraction; unrelated lines yield nothing") {
                    // Lines as printed to stderr by whisper-cli 1.9.2 (-pp) on an 88 s clip; the percentage is padded to width 3.
                    val expected =
                        listOf(
                            "system_info: n_threads = 4 / 8 | WHISPER : COREML = 0 | OPENVINO = 0 | CPU : SSE3 = 1 | SSSE3 = 1 | " +
                                "AVX = 1 | AVX2 = 1 | F16C = 1 | FMA = 1 | BMI2 = 1 | OPENMP = 1 | REPACK = 1 | " to null,
                            "main: processing 'long.wav' (1408000 samples, 88.0 sec), 4 threads, 1 processors, 5 beams + best of 5, " +
                                "lang = en, task = transcribe, timestamps = 1 ..." to null,
                            "" to null,
                            "whisper_print_progress_callback: progress =  27%" to 0.27,
                            "whisper_print_progress_callback: progress = 100%" to 1.0,
                            "output_json: saving output to 'long.json'" to null,
                        )

                    expected.map { (line, _) -> parseProgress(line) }.shouldBe(expected.map { (_, fraction) -> fraction })
                }
            }
        }
    })
