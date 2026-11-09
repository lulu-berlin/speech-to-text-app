package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.delay
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import stt.Integration
import stt.awaitServiceServing
import stt.startService
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Roadmap item 66, test-list D4: a `config.json` the service cannot read — cut short, unreadable, or naming a port out
 * of range — is replaced at the next start with the default port and a new secret (item 95), and a client that reads
 * `config.json` afterwards reaches the service with that secret. Like D1 it needs the default port free.
 */
class UnreadableConfigIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        /** Whether [file] appears within [timeout]. */
        suspend fun awaitFileWithin(
            file: Path,
            timeout: Duration,
        ): Boolean {
            val deadline = System.nanoTime() + timeout.inWholeNanoseconds
            while (System.nanoTime() < deadline) {
                if (file.exists()) return true
                delay(100)
            }
            return false
        }

        /** Starts a service whose `config.json` [breakConfig] breaks, and checks it was replaced and serves. */
        suspend fun assertReplaced(breakConfig: (ServiceDirs) -> Unit) {
            val temp = tempdir("stt-bad-config").toPath()
            val dirs = ServiceDirs(temp.resolve("config"), temp.resolve("runtime"))
            Files.createDirectories(dirs.configDir)
            configFile(dirs).writeText("""{"port":41999,"secret":"old-sec"}""")
            breakConfig(dirs)

            val service = startService(temp, null, temp.resolve("service.log"), dirs)
            try {
                awaitFileWithin(runtimeFile(dirs), 20.seconds) shouldBe true

                val config = readConfig(dirs)
                config.getValue("port").jsonPrimitive.int shouldBe 28763
                val secret = config.getValue("secret").jsonPrimitive.content
                secret.shouldNotBeBlank()
                (secret == "old-sec") shouldBe false
                URI(readRuntime(dirs).getValue("endpoint").jsonPrimitive.content).port shouldBe 28763
                awaitServiceServing(28763, dirs) shouldBe true
            } finally {
                service.destroyForcibly().waitFor()
            }
        }

        given("a config.json cut short in the middle of the secret") {
            `when`("the service starts") {
                then("it writes a new config.json with the default port and a new secret, and serves with them") {
                    val temp = tempdir("stt-unreadable-config").toPath()
                    val dirs = ServiceDirs(temp.resolve("config"), temp.resolve("runtime"))
                    Files.createDirectories(dirs.configDir)
                    configFile(dirs).writeText("""{"port":41999,"secret":"old-sec""")

                    val service = startService(temp, null, temp.resolve("service.log"), dirs)
                    try {
                        awaitFile(runtimeFile(dirs)) shouldBe true

                        val config = readConfig(dirs)
                        config.getValue("port").jsonPrimitive.int shouldBe 28763
                        val secret = config.getValue("secret").jsonPrimitive.content
                        secret.shouldNotBeBlank()
                        (secret == "old-sec") shouldBe false
                        URI(readRuntime(dirs).getValue("endpoint").jsonPrimitive.content).port shouldBe 28763
                        awaitServiceServing(28763, dirs) shouldBe true
                    } finally {
                        service.destroyForcibly().waitFor()
                    }
                }
            }
        }

        given("a config.json the service cannot read, and one naming a port out of range") {
            `when`("the service starts on each") {
                then("each is replaced with the default port and a new secret, and the service serves") {
                    assertReplaced { dirs ->
                        Files.setPosixFilePermissions(configFile(dirs), PosixFilePermissions.fromString("---------"))
                    }
                    assertReplaced { dirs ->
                        configFile(dirs).writeText("""{"port":70000,"secret":"old-sec"}""")
                    }
                }
            }
        }
    })
