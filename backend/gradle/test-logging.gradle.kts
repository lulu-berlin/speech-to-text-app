// Applied by build.gradle.kts to every Test task (roadmap item 77): a failing test prints its
// exception in full — message, causes and stack trace — in the console, so a CI job log names
// what failed (for UploadStoredInDatabaseSpec, the seen paths) without the HTML/XML reports.
// Gradle's default (SHORT) prints only the exception class and the failing line.
// TestLoggingIntegrationSpec applies this same file to a fixture build to prove it.
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}
