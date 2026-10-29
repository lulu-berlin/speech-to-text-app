package stt

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.withTimeout
import stt.process.ProcessRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.time.Duration.Companion.minutes

/**
 * Item 77, test-list item "A failing backend test's assertion message appears in the `gradle check` console
 * output, so a CI job log shows which file appeared": a fixture build, run by the same Gradle distribution as this
 * build, applies the backend's own test-logging script (`gradle/test-logging.gradle.kts`, the one
 * `build.gradle.kts` applies) to a `Test` task that runs the failing [TestLoggingProbe], and the build's console
 * output carries the probe's assertion message. A real process, hence Integration (ADR 0021).
 */
class TestLoggingIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a fixture build that applies the backend's test-logging script to a task running a failing test") {
            `when`("the build runs and the test fails") {
                then("the console output carries the failing test's assertion message") {
                    val fixture = tempdir("test-logging-fixture").toPath()
                    val testLogging = Path.of("gradle/test-logging.gradle.kts").toAbsolutePath()
                    Files.writeString(fixture.resolve("settings.gradle.kts"), "rootProject.name = \"test-logging-fixture\"\n")
                    Files.writeString(
                        fixture.resolve("build.gradle.kts"),
                        """
                        // The core java plugin gives the probe task its report and result directories.
                        plugins { java }

                        apply(from = file("${testLogging.invariantSeparatorsPathString}"))

                        val probeClasspath = files(providers.gradleProperty("probeClasspath").get().split(File.pathSeparator))

                        tasks.register<Test>("probe") {
                            testClassesDirs = probeClasspath.filter { it.isDirectory }
                            classpath = probeClasspath
                            useJUnitPlatform { includeEngines("kotest") }
                            filter { includeTestsMatching("stt.TestLoggingProbe") }
                            systemProperty("stt.testLoggingProbe", "fail")
                        }
                        """.trimIndent(),
                    )

                    val launcher = gradleLauncher()
                    val command =
                        listOf(
                            launcher.toString(),
                            "--no-daemon",
                            "--console=plain",
                            "-p",
                            fixture.toString(),
                            "probe",
                            "-PprobeClasspath=${System.getProperty("stt.test.runtimeClasspath")}",
                        )

                    val result = withTimeout(5.minutes) { ProcessRunner().run(command) }

                    result.exitCode shouldNotBe 0
                    (result.stdout + result.stderr).joinToString("\n") shouldContain "the seen paths are [$PROBE_PATH]"
                }
            }
        }
    })
