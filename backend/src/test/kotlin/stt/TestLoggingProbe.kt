package stt

import io.kotest.assertions.fail
import io.kotest.core.spec.style.BehaviorSpec

/**
 * Not a test of the app: the failing test [TestLoggingIntegrationSpec]'s fixture build runs, to see where its
 * assertion message ends up. It is disabled unless the fixture sets `stt.testLoggingProbe=fail`, so a normal run
 * reports it as skipped.
 */
class TestLoggingProbe :
    BehaviorSpec({
        given("the test-logging probe") {
            `when`("the fixture build enables it") {
                then("it fails with a message that names a path").config(
                    enabled = System.getProperty("stt.testLoggingProbe") == "fail",
                ) {
                    fail("the seen paths are [$PROBE_PATH]")
                }
            }
        }
    })

/** The path the probe's failure message names; [TestLoggingIntegrationSpec] looks for it in the build output. */
internal const val PROBE_PATH = "/probe/leaked.part"
