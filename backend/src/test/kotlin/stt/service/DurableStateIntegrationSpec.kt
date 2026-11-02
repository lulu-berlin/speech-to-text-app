package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import stt.Integration
import stt.awaitServiceServing
import stt.freePort
import stt.startService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * Roadmap item 66, test list item 7: the durable/ephemeral split survives a logout or reboot. The runtime directory
 * (`$XDG_RUNTIME_DIR` and its analogues) is cleared then; the config directory is not. The next start serves on the
 * durable port with the same per-install secret, its fresh `runtime.json` advertises that port, and the secret never
 * reached the runtime directory. The second start offers another port as its first-run default, so only the durable
 * `config.json` can bring the first port back.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class DurableStateIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a service that ran once and stopped") {
            `when`("the runtime directory is cleared, as a logout or reboot does, and the service starts again") {
                then(
                    "it serves on the durable port with the same secret, advertises that port, and no secret is in the runtime directory",
                ) {
                    val dataDir = tempdir("stt-durable-data").toPath()
                    val dirs = ServiceDirs(tempdir("stt-durable-config").toPath(), tempdir("stt-durable-runtime").toPath())
                    val logs = tempdir("stt-durable-logs").toPath()
                    val durablePort = freePort()

                    val first = startService(dataDir, durablePort, logs.resolve("first.log"), dirs)
                    try {
                        awaitServiceServing(durablePort, dirs) shouldBe true
                    } finally {
                        first.destroy()
                        first.waitFor(30, TimeUnit.SECONDS)
                        first.destroyForcibly().waitFor()
                    }
                    val secret = readConfig(dirs).getValue("secret").jsonPrimitive.content
                    dirs.runtimeDir.deleteRecursively()

                    val otherPort = freePort()
                    val second = startService(dataDir, otherPort, logs.resolve("second.log"), dirs)
                    try {
                        awaitServiceServing(durablePort, dirs) shouldBe true

                        val config = readConfig(dirs)
                        config.getValue("port").jsonPrimitive.int shouldBe durablePort
                        config.getValue("secret").jsonPrimitive.content shouldBe secret
                        val runtime = readRuntime(dirs)
                        runtime.getValue("endpoint").jsonPrimitive.content shouldBe "http://127.0.0.1:$durablePort"
                        filesHolding(dirs.runtimeDir, secret).shouldBeEmpty()
                    } finally {
                        second.destroyForcibly().waitFor()
                    }
                }
            }
        }
    })

/** The files under [dir] whose bytes contain [secret]. */
private fun filesHolding(
    dir: Path,
    secret: String,
): List<Path> =
    Files.walk(dir).use { paths ->
        paths
            .filter { it.isRegularFile() && String(it.readBytes(), Charsets.ISO_8859_1).contains(secret) }
            .toList()
    }
