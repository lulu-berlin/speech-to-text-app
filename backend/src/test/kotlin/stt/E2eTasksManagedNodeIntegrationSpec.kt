package stt

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import stt.process.ProcessResult
import stt.process.ProcessRunner
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Roadmap item 79, review finding 1 (test-list item "The e2e Gradle tasks start the managed Node by its absolute
 * path"): a machine without Node on PATH runs the e2e tasks, because the build starts the Node it downloaded. The
 * task's environment already holds that Node's directory on PATH, but Gradle's Exec resolves the *program* on the
 * Gradle process's PATH, so a bare `node` never reaches it.
 *
 * The nested build downloads the managed Node first (`:frontend:nodeSetup`): looking for it before that step skipped
 * this spec on every host that had not installed it yet — which is every host the item is about. The skip now covers
 * only a downloaded Node this host cannot execute. The chain then matches `gradle e2e`, `pnpmSetup` included; that
 * task starts npm through its `#!/usr/bin/env node` shebang, so it stays out only where `/usr/bin/env` does not exist
 * at all — a container built without it, never a supported host. A real build in a real process, hence Integration
 * (ADR 0021).
 */
class E2eTasksManagedNodeIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a host whose PATH holds no node") {
            val repoRoot = Path.of("..").toAbsolutePath().normalize()
            val hostPath = System.getenv()["PATH"] ?: error("no PATH in the environment")
            // Dropping every entry that holds a node executable is what a machine without Node looks like.
            val pathWithoutNode =
                hostPath
                    .split(File.pathSeparator)
                    .filterNot { Files.exists(Path.of(it, "node")) || Files.exists(Path.of(it, "node.exe")) }
                    .joinToString(File.pathSeparator)

            `when`("the e2e dependency install runs") {
                then("it succeeds, because the task starts the managed Node itself") {
                    skipUnless(
                        !System.getProperty("os.name").startsWith("Windows"),
                        "POSIX-only: the command passes the stripped PATH through `env`, and no Windows CI leg runs " +
                            "integrationTest (Windows (e2e) proves the same behavior end to end)",
                    )
                    check(pathWithoutNode != hostPath) { "test setup: no node found on the host PATH" }

                    // Download first: `nodeSetup` installs the Node the e2e tasks start by absolute path, so its
                    // result is the precondition of the skip below, never a reason to skip before it ran.
                    val download = withTimeout(10.minutes) { runGradle(nodeSetup(repoRoot, pathWithoutNode)) }
                    withClue((download.stdout + download.stderr).joinToString("\n")) { download.exitCode shouldBe 0 }

                    val node = managedNode()
                    check(node != null) { "test setup: :frontend:nodeSetup installed no Node under ${gradleHome()}" }
                    skipUnless(
                        starts(node),
                        "the managed Node exists at $node but cannot execute on this host (a sandbox mount, a missing " +
                            "loader); the Linux and macOS integration legs run this",
                    )

                    // npm's shebang names /usr/bin/env; a host without that interpreter cannot start pnpmSetup, so
                    // the chain then keeps to the tasks that run the managed Node directly.
                    val includePnpmSetup = Files.isExecutable(Path.of("/usr/bin/env"))
                    val result =
                        withTimeout(10.minutes) {
                            runGradle(e2eInstall(repoRoot, pathWithoutNode, includePnpmSetup))
                        }
                    val output = (result.stdout + result.stderr).joinToString("\n")
                    withClue(output) { result.exitCode shouldBe 0 }
                }
            }
        }
    })

/**
 * [assumption], with [reason] printed when it fails: the JUnit XML records only that the test skipped, so the
 * reason has to reach the run's output on its own.
 */
private fun skipUnless(
    assumption: Boolean,
    reason: String,
) {
    if (!assumption) println("E2eTasksManagedNodeIntegrationSpec skipped: $reason")
    assumeTrue(assumption, reason)
}

/** `:frontend:nodeSetup` alone: the step that downloads the Node the e2e tasks start. */
private fun nodeSetup(
    repoRoot: Path,
    pathWithoutNode: String,
): List<String> = nestedBuild(repoRoot, pathWithoutNode, listOf(":frontend:nodeSetup"))

/**
 * The real `e2eInstall` task, for which node-gradle's `:frontend:pnpmSetup` installs pnpm. [includePnpmSetup] is
 * false only where `/usr/bin/env` is missing: npm's `#!/usr/bin/env node` shebang cannot start without it, and no
 * supported host lacks it.
 */
private fun e2eInstall(
    repoRoot: Path,
    pathWithoutNode: String,
    includePnpmSetup: Boolean,
): List<String> = nestedBuild(repoRoot, pathWithoutNode, e2eTasks(includePnpmSetup))

/** The `gradle e2e` chain this spec runs, with pnpm's install excluded where `/usr/bin/env` is missing. */
private fun e2eTasks(includePnpmSetup: Boolean): List<String> =
    listOf(":frontend:nodeSetup") +
        (if (includePnpmSetup) listOf(":frontend:pnpmSetup") else listOf("-x", ":frontend:pnpmSetup")) +
        listOf("e2eInstall")

/** [tasks] of the repository's build, with [pathWithoutNode] as PATH and no daemon of its own. */
private fun nestedBuild(
    repoRoot: Path,
    pathWithoutNode: String,
    tasks: List<String>,
): List<String> =
    withPath(
        pathWithoutNode,
        listOf(gradleLauncher().toString(), "--no-daemon", "--console=plain", "-p", repoRoot.toString()) + tasks,
    )

/** [command] in a real process: the nested build is what makes this spec Integration. */
private suspend fun runGradle(command: List<String>): ProcessResult =
    ProcessRunner(gracePeriod = 2.seconds, startProcess = ProcessBuilder::start).run(command)

/** The Node `:frontend:nodeSetup` installed under GRADLE_USER_HOME, or null when it installed none. */
private fun managedNode(): Path? {
    val nodejs = gradleHome().resolve("stt-app/node/nodejs")
    if (!Files.isDirectory(nodejs)) return null
    return Files.list(nodejs).use { dirs ->
        dirs
            .flatMap { dir ->
                listOf(dir.resolve("bin/node"), dir.resolve("node.exe")).filter(Files::exists).stream()
            }.findFirst()
            .orElse(null)
    }
}

/** Whether [node] starts on this host at all: some hosts refuse to execute it (a sandbox mount, a missing loader). */
private fun starts(node: Path): Boolean =
    runCatching {
        runBlocking { runGradle(listOf(node.toString(), "--version")) }.exitCode == 0
    }.getOrDefault(false)

private fun gradleHome(): Path = System.getenv("GRADLE_USER_HOME")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".gradle")
