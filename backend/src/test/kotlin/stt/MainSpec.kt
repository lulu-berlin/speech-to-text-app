package stt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import stt.config.Config
import stt.config.TEST_DATA_DIR
import java.nio.file.Path

class MainSpec :
    BehaviorSpec({

        fun captureLogs(): Triple<Logger, ListAppender<ILoggingEvent>, Level?> {
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            val logger = LoggerFactory.getLogger("stt.Main") as Logger
            val previousLevel = logger.level
            logger.level = Level.WARN
            logger.addAppender(appender)
            return Triple(logger, appender, previousLevel)
        }

        given("a configuration without STT_STATIC_DIR") {
            `when`("checking the boot warning") {
                then("a warning naming STT_STATIC_DIR is logged, none when it is set") {
                    val (logger, appender, previousLevel) = captureLogs()
                    try {
                        warnIfStaticDirUnset(Config(dataDir = Path.of(TEST_DATA_DIR)))
                        warnIfStaticDirUnset(Config(dataDir = Path.of(TEST_DATA_DIR), staticDir = Path.of("/some/dir")))
                        val warnings = appender.list.filter { it.level == Level.WARN }
                        warnings.size.shouldBe(1)
                        warnings.single().formattedMessage.shouldContain("STT_STATIC_DIR")
                    } finally {
                        logger.level = previousLevel
                        logger.detachAppender(appender)
                        appender.stop()
                    }
                }
            }
        }
    })
