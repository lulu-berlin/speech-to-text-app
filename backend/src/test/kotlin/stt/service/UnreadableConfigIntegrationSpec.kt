package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import stt.Integration
import stt.awaitServiceServing
import stt.startService
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * Roadmap item 66, test-list D4: a `config.json` the service cannot read — here cut short, as a crash while it was
 * written leaves it — is replaced at the next start with the default port and a new secret, and a client that reads
 * `config.json` afterwards reaches the service with that secret. Like D1 it needs the default port free.
 */
class UnreadableConfigIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

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
    })
