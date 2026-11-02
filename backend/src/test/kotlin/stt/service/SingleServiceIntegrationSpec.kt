package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import stt.Integration
import stt.awaitServiceServing
import stt.freePort
import stt.startService
import java.util.concurrent.TimeUnit

/**
 * Roadmap item 66, test list item 1: one service per user. The app started in app mode (`service`) takes the
 * per-user instance: the durable `config.json` in the config directory holds the port it serves and the
 * per-install secret, and the ephemeral `runtime.json` in the runtime directory names the running instance. A
 * second launch for the same user defers to that instance instead of starting another. Two real JVMs, because
 * only separate processes show the per-user lock.
 */
class SingleServiceIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("an empty per-user config directory and runtime directory") {
            `when`("the app is started as the service twice for that user") {
                then("the second launch exits and the first keeps serving, as config.json and runtime.json describe") {
                    val dataDir = tempdir("stt-service-data").toPath()
                    val configDir = tempdir("stt-service-config").toPath()
                    val runtimeDir = tempdir("stt-service-runtime").toPath()
                    val logs = tempdir("stt-service-logs").toPath()
                    val port = freePort()
                    val dirs = ServiceDirs(configDir, runtimeDir)
                    val first = startService(dataDir, port, logs.resolve("first.log"), dirs)
                    var second: Process? = null
                    try {
                        awaitServiceServing(port, dirs) shouldBe true

                        second = startService(dataDir, port, logs.resolve("second.log"), dirs)

                        second.waitFor(30, TimeUnit.SECONDS) shouldBe true
                        second.exitValue() shouldBe 0
                        first.isAlive shouldBe true
                        awaitServiceServing(port, dirs) shouldBe true
                        val config = readConfig(dirs)
                        config.getValue("port").jsonPrimitive.int shouldBe port
                        config
                            .getValue("secret")
                            .jsonPrimitive.content
                            .shouldNotBeBlank()
                        val runtime = readRuntime(dirs)
                        runtime.getValue("pid").jsonPrimitive.long shouldBe first.pid()
                        runtime.getValue("endpoint").jsonPrimitive.content shouldBe "http://127.0.0.1:$port"
                    } finally {
                        second?.destroyForcibly()?.waitFor()
                        first.destroyForcibly().waitFor()
                    }
                }
            }
        }
    })
