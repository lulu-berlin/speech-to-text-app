package stt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Runs [block] with the events of the logger named [loggerName] at WARN and above captured in the
 * list passed to it; the logger's level and appenders are restored afterwards.
 */
internal inline fun <T> withCapturedWarnings(
    loggerName: String,
    block: (events: List<ILoggingEvent>) -> T,
): T {
    val appender = ListAppender<ILoggingEvent>()
    appender.start()
    val logger = LoggerFactory.getLogger(loggerName) as Logger
    val previousLevel = logger.level
    logger.level = Level.WARN
    logger.addAppender(appender)
    try {
        return block(appender.list)
    } finally {
        logger.level = previousLevel
        logger.detachAppender(appender)
        appender.stop()
    }
}
