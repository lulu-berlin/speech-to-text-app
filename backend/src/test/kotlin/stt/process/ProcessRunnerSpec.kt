package stt.process

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeout
import stt.Integration
import kotlin.time.Duration.Companion.seconds

class ProcessRunnerSpec :
    BehaviorSpec({
        tags(Integration)

        given("a command that prints three lines and exits successfully") {
            `when`("running it") {
                then("the exit code is 0 and each stdout line reaches the callback and the result in order") {
                    val expectedLines = listOf("one", "two", "three")
                    val delivered = mutableListOf<String>()

                    val result =
                        withTimeout(10.seconds) {
                            ProcessRunner().run(
                                listOf("sh", "-c", "echo one; echo two; echo three"),
                                onStdoutLine = { delivered += it },
                            )
                        }

                    result.exitCode.shouldBe(0)
                    result.stdout.shouldBe(expectedLines)
                    delivered.shouldBe(expectedLines)
                }
            }
        }

        given("a command that writes two stderr lines and exits with code 3") {
            `when`("running it") {
                then("the result carries exit code 3 and the stderr lines in order") {
                    val result =
                        withTimeout(10.seconds) {
                            ProcessRunner().run(
                                listOf("sh", "-c", "echo first-error >&2; echo second-error >&2; exit 3"),
                            )
                        }

                    result.exitCode.shouldBe(3)
                    result.stderr.shouldBe(listOf("first-error", "second-error"))
                }
            }
        }
    })
