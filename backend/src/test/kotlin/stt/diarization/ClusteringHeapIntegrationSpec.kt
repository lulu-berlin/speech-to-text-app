package stt.diarization

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.process.ProcessResult
import stt.process.ProcessRunner
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * Item 23's memory claim — clustering 3000 segments allocates no `O(n²)` distance matrix — pinned
 * without measuring a heap. The workload runs in a child JVM whose max heap sits between the
 * clustering's footprint and the matrix's ([ClusteringHeapProbe.MAX_HEAP]), so *finishing* is the
 * assertion; the second scenario allocates the matrix in the same JVM and must run out of memory,
 * which is what makes the bound meaningful. The shared test JVM's ambient heap is never measured:
 * that measurement flaked under load and inside the Nix sandbox, where it failed `nix build`'s
 * `gradle check` on `main`. A real process, hence Integration (ADR 0021).
 */
class ClusteringHeapIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a child JVM with a max heap below the 3000² distance matrix (${ClusteringHeapProbe.MAX_HEAP})") {
            `when`("the 3000-segment clustering runs in it") {
                then("it finishes, so the matrix was never allocated") {
                    val result = runProbe()

                    result.exitCode shouldBe 0
                    result.stdout.joinToString("\n") shouldContain "clustered: ${ClusteringHeapProbe.SEGMENTS - 1}"
                }
            }

            `when`("the item 09 matrix is allocated in it instead") {
                then("it runs out of memory, so the heap bound really excludes the matrix") {
                    val result = runProbe("matrix")

                    (result.exitCode != 0).shouldBeTrue()
                    (result.stderr + result.stdout).joinToString("\n") shouldContain "out of memory"
                }
            }
        }
    })

/** Runs [ClusteringHeapProbe] in a child JVM with the test runtime classpath and [args]. */
private suspend fun runProbe(vararg args: String): ProcessResult {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    val classpath =
        System.getProperty("stt.test.runtimeClasspath")
            ?: error("backend/build.gradle.kts must set stt.test.runtimeClasspath for the child JVM")
    val command =
        listOf(java, "-Xmx${ClusteringHeapProbe.MAX_HEAP}", "-cp", classpath, "stt.diarization.ClusteringHeapProbe") +
            args
    return withTimeout(5.minutes) { ProcessRunner().run(command) }
}
