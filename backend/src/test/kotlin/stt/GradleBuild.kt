package stt

import java.nio.file.Files
import java.nio.file.Path

/**
 * The Gradle launcher of the distribution running this build (backend/build.gradle.kts passes its home in
 * `stt.test.gradleHome`), so a spec can run a nested build with the same Gradle: the devenv's locally, the
 * wrapper's in CI.
 */
fun gradleLauncher(): Path {
    val gradleHome =
        System.getProperty("stt.test.gradleHome")
            ?: error("backend/build.gradle.kts must set stt.test.gradleHome for a nested build")
    return (
        if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("gradle.bat", "gradlew.bat", "gradlew")
        } else {
            listOf("gradle", "gradlew")
        }
    ).map { Path.of(gradleHome, "bin", it) }
        .firstOrNull(Files::exists)
        ?: error("no Gradle launcher in $gradleHome/bin")
}

/**
 * [command] run with [path] as its PATH. A `ProcessBuilder` inherits its own environment, so `env` is what
 * carries a substituted PATH into a nested build.
 */
fun withPath(
    path: String,
    command: List<String>,
): List<String> = listOf("env", "PATH=$path") + command
