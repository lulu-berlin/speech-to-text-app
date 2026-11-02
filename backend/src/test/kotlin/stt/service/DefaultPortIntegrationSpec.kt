package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import stt.Integration
import stt.awaitServiceServing
import stt.startService
import java.net.URI

/**
 * Roadmap item 66, test-list item D1: the service has a default port, 28763, settled on 2026-10-29 because it is
 * unassigned at IANA and below the ephemeral range of every OS. The first start with nothing configured takes it and
 * persists it; `STT_PORT` seeds a different first port at the user's request.
 */
class DefaultPortIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a user with no config.json and no STT_PORT") {
            `when`("the service starts for the first time") {
                then("it serves on the default port 28763 and persists it") {
                    val temp = tempdir("stt-default-port").toPath()
                    val dirs = ServiceDirs(temp.resolve("config"), temp.resolve("runtime"))
                    val service = startService(temp, null, temp.resolve("service.log"), dirs)
                    try {
                        awaitFile(runtimeFile(dirs)) shouldBe true

                        val endpoint = URI(readRuntime(dirs).getValue("endpoint").jsonPrimitive.content)
                        endpoint.port shouldBe 28763
                        readConfig(dirs).getValue("port").jsonPrimitive.int shouldBe 28763
                        awaitServiceServing(28763, dirs) shouldBe true
                    } finally {
                        service.destroyForcibly().waitFor()
                    }
                }
            }
        }
    })
