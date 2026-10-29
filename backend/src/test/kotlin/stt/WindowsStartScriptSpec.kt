package stt

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import java.io.File

/**
 * Roadmap item 79 (discovered): cmd.exe refuses a command line longer than 8,191 characters ("The input line is too
 * long"), and the generated `stt-backend.bat` sets its classpath on one line. The line is measured the way cmd.exe
 * sees it — every `%APP_HOME%` expanded to a deep checkout path — so the start script, and with it `gradlew.bat run`
 * and the Windows journey, starts at any checkout depth.
 */
class WindowsStartScriptSpec :
    BehaviorSpec({
        given("the Windows start script the build generates") {
            val script =
                File(
                    requireNotNull(System.getProperty("stt.test.windowsStartScript")) {
                        "stt.test.windowsStartScript is unset: run the spec through the build's test task"
                    },
                )
            `when`("cmd.exe expands its classpath line under a deep checkout path") {
                then("the line stays within cmd.exe's 8,191-character limit") {
                    val line = script.readLines().single { it.startsWith("set CLASSPATH=") }

                    line.replace("%APP_HOME%", DEEP_CHECKOUT_APP_HOME).length shouldBeLessThanOrEqual CMD_LINE_LIMIT
                }
            }
        }
    })

private const val CMD_LINE_LIMIT = 8191

// ~300 characters: deeper than the CI runner's 76, and than any checkout a user plausibly has.
private val DEEP_CHECKOUT_APP_HOME =
    "D:\\" + "nested-checkout-directory\\".repeat(10) + "backend\\build\\install\\stt-backend"
