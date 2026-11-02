package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.ints.shouldNotBeExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import stt.Integration
import stt.awaitServiceServing
import stt.startService
import java.net.ServerSocket
import java.net.URI

/**
 * Roadmap item 66, test list item 6 (the service half): when a foreign program holds the port the service would take,
 * the service serves on a free port instead, persists it as its durable port in `config.json`, and advertises it in
 * `runtime.json`, the file the tray and `stt-app open` read (the client reading it is `client/stt-client`'s test).
 */
class PortFallbackIntegrationSpec :
    BehaviorSpec(
        {
            tags(Integration)

            given("a port held by a foreign program") {
                `when`("the service starts with that port as its port") {
                    then("it serves on a free port, persists it in config.json and advertises it in runtime.json") {
                        val dataDir = tempdir("stt-fallback-data").toPath()
                        val dirs = ServiceDirs(tempdir("stt-fallback-config").toPath(), tempdir("stt-fallback-runtime").toPath())
                        val logs = tempdir("stt-fallback-logs").toPath()
                        ServerSocket(0).use { foreign ->
                            val taken = foreign.localPort
                            val service = startService(dataDir, taken, logs.resolve("service.log"), dirs)
                            try {
                                awaitFile(runtimeFile(dirs)) shouldBe true
                                val endpoint = URI(readRuntime(dirs).getValue("endpoint").jsonPrimitive.content)
                                val actual = endpoint.port

                                actual shouldNotBeExactly taken
                                endpoint.host shouldBe "127.0.0.1"
                                awaitServiceServing(actual, dirs) shouldBe true
                                readConfig(dirs).getValue("port").jsonPrimitive.int shouldBe actual
                            } finally {
                                service.destroyForcibly().waitFor()
                            }
                        }
                    }
                }
            }
        },
    )
