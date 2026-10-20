package stt.process

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ProcessTreeSignalsSpec :
    BehaviorSpec({
        given("the darwin signal numbers (macOS/BSD: SIGSTOP 17, SIGCONT 19)") {
            `when`("the process tree is paused and resumed") {
                then("pause sends SIGSTOP 17 and resume sends SIGCONT 19") {
                    val sent = mutableListOf<Int>()
                    val tree =
                        ProcessTree(4242, childAlive = { false }, signals = ProcessSignals.DARWIN) { _, signal ->
                            sent += signal
                            0
                        }

                    tree.pause()
                    tree.resume()

                    sent.shouldBe(listOf(17, 19))
                    ProcessSignals.forOsName("Mac OS X").shouldBe(ProcessSignals.DARWIN)
                }
            }
        }

        given("the linux signal numbers (SIGSTOP 19, SIGCONT 18)") {
            `when`("the process tree is paused and resumed") {
                then("pause sends SIGSTOP 19 and resume sends SIGCONT 18, unchanged") {
                    val sent = mutableListOf<Int>()
                    val tree =
                        ProcessTree(4242, childAlive = { false }, signals = ProcessSignals.LINUX) { _, signal ->
                            sent += signal
                            0
                        }

                    tree.pause()
                    tree.resume()

                    sent.shouldBe(listOf(19, 18))
                    ProcessSignals.forOsName("Linux").shouldBe(ProcessSignals.LINUX)
                }
            }
        }
    })
