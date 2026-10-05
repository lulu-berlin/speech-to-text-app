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
import java.nio.file.Path

class MainSpec :
    BehaviorSpec({

        fun captureLogs(): Pair<Logger, ListAppender<ILoggingEvent>> {
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            val logger = LoggerFactory.getLogger("stt.Main") as Logger
            logger.level = Level.WARN
            logger.addAppender(appender)
            return logger to appender
        }

        given("a configuration without STT_STATIC_DIR") {
            `when`("checking the boot warning") {
                then("a warning naming STT_STATIC_DIR is logged, none when it is set") {
                    val (logger, appender) = captureLogs()
                    try {
                        warnIfStaticDirUnset(Config())
                        warnIfStaticDirUnset(Config(staticDir = Path.of("/some/dir")))
                        val warnings = appender.list.filter { it.level == Level.WARN }
                        warnings.size.shouldBe(1)
                        warnings.single().formattedMessage.shouldContain("STT_STATIC_DIR")
                    } finally {
                        logger.detachAppender(appender)
                        appender.stop()
                    }
                }
            }
        }
    })
