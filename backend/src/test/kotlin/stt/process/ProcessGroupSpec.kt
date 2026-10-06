@file:OptIn(ExperimentalCoroutinesApi::class)

package stt.process

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ProcessGroupSpec :
    BehaviorSpec({

        fun captureLogs(): Triple<Logger, ListAppender<ILoggingEvent>, Level?> {
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            val logger = LoggerFactory.getLogger(ProcessGroup::class.java) as Logger
            val previousLevel = logger.level
            logger.level = Level.WARN
            logger.addAppender(appender)
            return Triple(logger, appender, previousLevel)
        }

        given("a process group whose members outlive SIGKILL (kill(2) keeps reporting members)") {
            `when`("it is terminated with a 2 s grace period") {
                then("terminate gives up one grace period after SIGKILL and logs a warning naming the group") {
                    val sent = mutableListOf<Int>()
                    val group =
                        ProcessGroup(4242) { _, signal ->
                            if (signal != 0) sent += signal
                            0
                        }
                    val (logger, appender, previousLevel) = captureLogs()
                    try {
                        runTest {
                            withTimeout(1.minutes) { group.terminate(2.seconds) }
                            // Grace period after SIGTERM, then at most one more after SIGKILL (ADR 0020).
                            currentTime.shouldBe(4_000)
                        }

                        // SIGCONT, SIGTERM, SIGKILL (Linux numbers).
                        sent.shouldBe(listOf(18, 15, 9))
                        val warnings = appender.list.filter { it.level == Level.WARN }
                        warnings.size.shouldBe(1)
                        warnings.single().formattedMessage.shouldContain("4242")
                    } finally {
                        logger.level = previousLevel
                        logger.detachAppender(appender)
                        appender.stop()
                    }
                }
            }
        }
    })
