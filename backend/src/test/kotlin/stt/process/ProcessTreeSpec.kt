@file:OptIn(ExperimentalCoroutinesApi::class)

package stt.process

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import stt.withCapturedLogs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ProcessTreeSpec :
    BehaviorSpec({
        given("a process group whose members outlive SIGKILL (kill(2) keeps reporting members)") {
            `when`("it is terminated with a 2 s grace period") {
                then("terminate gives up one grace period after SIGKILL and logs a warning naming the group") {
                    val sent = mutableListOf<Int>()
                    val group =
                        ProcessTree(4242, childAlive = { false }, signals = ProcessSignals.LINUX) { _, signal ->
                            if (signal != 0) sent += signal
                            0
                        }
                    withCapturedLogs(ProcessTree::class.java.name) { events ->
                        runTest {
                            withTimeout(1.minutes) { group.terminate(2.seconds) }
                            // Grace period after SIGTERM, then at most one more after SIGKILL (ADR 0020).
                            currentTime.shouldBe(4_000)
                        }

                        // SIGCONT, SIGTERM, SIGKILL (Linux numbers).
                        sent.shouldBe(listOf(18, 15, 9))
                        val warnings = events.filter { it.level == Level.WARN }
                        warnings.size.shouldBe(1)
                        warnings.single().formattedMessage.shouldContain("4242")
                    }
                }
            }
        }
    })
