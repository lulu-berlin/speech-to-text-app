package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import stt.Integration
import stt.awaitServiceServing
import stt.freePort
import stt.startService
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/**
 * Roadmap item 66, test-list D3 (the service half): a clean stop removes `runtime.json`, so after it no file claims
 * that a service answers. A crash cannot remove it; that leftover is the clients' half of D3.
 */
class CleanStopIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a running service that advertises itself in runtime.json") {
            `when`("it is stopped cleanly, as a logout or `stt-app quit` stops it") {
                then("it exits and runtime.json is gone, while config.json stays") {
                    val dirs = ServiceDirs(tempdir("stt-stop-config").toPath(), tempdir("stt-stop-runtime").toPath())
                    val port = freePort()
                    val service =
                        startService(
                            tempdir("stt-stop-data").toPath(),
                            port,
                            tempdir("stt-stop-logs").toPath().resolve("service.log"),
                            dirs,
                        )
                    try {
                        awaitServiceServing(port, dirs) shouldBe true
                        runtimeFile(dirs).exists() shouldBe true

                        service.destroy()
                        service.waitFor(30, TimeUnit.SECONDS) shouldBe true

                        runtimeFile(dirs).exists() shouldBe false
                        configFile(dirs).exists() shouldBe true
                    } finally {
                        service.destroyForcibly().waitFor()
                    }
                }
            }
        }
    })
