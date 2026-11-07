package stt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Item 93's Logback edge: `logback.xml`'s output pattern is what operators read, and logback renders it
 * itself — the `%replace` that hides the task marker when no task logs. Rendered through the configured
 * layout, so a Logback that pads levels or expands `%X` differently shows up here instead of in the logs.
 */
class LogFormatSpec :
    BehaviorSpec({
        given("the backend's configured root logger") {
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger

            `when`("asking for its console appender and level") {
                then("it logs at INFO through the STDOUT appender") {
                    root.level shouldBe Level.INFO
                    root.getAppender("STDOUT").shouldBeInstanceOf<ConsoleAppender<*>>()
                }
            }
            `when`("a line is logged outside a task") {
                then("the pattern renders the time, a padded level, the logger and the message, with no task marker") {
                    val line = render(root, message = "hello")

                    // `%n` is the platform's line separator (\r\n on Windows), so the terminator is
                    // dropped before matching: the pattern's padding is what this asserts.
                    line.trimEnd('\r', '\n') shouldMatch
                        Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} INFO {2}stt\.LogFormatProbe - hello$""")
                }
            }
            `when`("a line is logged while a task's job runs") {
                then("the task's id appears between the logger and the separator") {
                    MDC.put("taskId", "7")
                    val line =
                        try {
                            render(root, message = "hello")
                        } finally {
                            MDC.remove("taskId")
                        }

                    line.trimEnd('\r', '\n') shouldMatch
                        Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} INFO {2}stt\.LogFormatProbe \[task 7\] - hello$""")
                }
            }
        }
    })

/** Renders one INFO line from [loggerName] through the appender's configured layout. */
private fun render(
    root: Logger,
    message: String,
): String {
    val appender = root.getAppender("STDOUT") as ConsoleAppender<ILoggingEvent>
    val layout = (appender.encoder as LayoutWrappingEncoder<ILoggingEvent>).getLayout()
    val logger = LoggerFactory.getLogger("stt.LogFormatProbe") as Logger
    val event = LoggingEvent("stt.LogFormatProbe", logger, Level.INFO, message, null, null)

    return layout.doLayout(event)
}
