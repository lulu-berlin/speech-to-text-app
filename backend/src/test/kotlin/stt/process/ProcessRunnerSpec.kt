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
                    val delivered = mutableListOf<String>()

                    val result =
                        withTimeout(10.seconds) {
                            ProcessRunner().run(
                                listOf("sh", "-c", "echo one; echo two; echo three"),
                                onStdoutLine = { delivered += it },
                            )
                        }

                    result.exitCode.shouldBe(0)
                    result.stdout.shouldBe(listOf("one", "two", "three"))
                    delivered.shouldBe(listOf("one", "two", "three"))
                }
            }
        }
    })
