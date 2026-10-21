package stt.transcribe

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import stt.Integration
import stt.process.ProcessResult
import stt.process.ProcessRunner

/**
 * Roadmap item 48: the shipped whisper-cli must accept the flags `DeviceArgs` emits — the 1.8.4
 * binary (the Gradle bootstrap's managed tool), not an assumption carried over from 1.9.x docs.
 * `--help` lists them, and a real run accepts both on the tiny model.
 */
class WhisperCliFlagsSpec :
    BehaviorSpec({
        tags(Integration)

        given("the shipped whisper-cli") {
            `when`("asked for its help") {
                then("it lists the --device and --no-gpu flags DeviceArgs uses") {
                    val result = run(listOf("whisper-cli", "--help"))
                    val output = (result.stdout + result.stderr).joinToString("\n")

                    result.exitCode shouldBe 0
                    output shouldContain "--device"
                    output shouldContain "--no-gpu"
                }
            }

            `when`("transcribing the JFK clip with --no-gpu and then with --device 0") {
                then("the shipped binary accepts both instead of rejecting an unknown option") {
                    val model = requireEnv("TEST_WHISPER_TINY_EN_MODEL")
                    val clip = requireEnv("TEST_JFK_WAV")

                    val cpu = run(listOf("whisper-cli", "-m", model, "-f", clip, "--no-gpu"))
                    val device = run(listOf("whisper-cli", "-m", model, "-f", clip, "--device", "0"))

                    cpu.exitCode shouldBe 0
                    device.exitCode shouldBe 0
                }
            }
        }
    })

/** `runTest` returns the test result, not the body's value; keep the process result out of it. */
private fun run(command: List<String>): ProcessResult {
    lateinit var result: ProcessResult
    runTest { result = ProcessRunner().run(command) }
    return result
}

private fun requireEnv(name: String): String = System.getenv(name) ?: error("$name is unset")
