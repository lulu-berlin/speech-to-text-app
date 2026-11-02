package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import stt.Integration
import stt.awaitServiceServing
import stt.freePort
import stt.startService
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Roadmap item 66, test list item 9 (the trust boundary's files): the service keeps its bootstrap state private to the
 * user. `config.json` — which holds the per-install secret — is readable by its owner only, and the two per-user
 * directories are closed to everybody else, so neither another account on the machine nor a world-readable backup can
 * carry the secret. POSIX permissions: Windows uses a user-only ACL and its leg is the packaged CI job.
 */
class PrivateFilesIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a fresh per-user config directory and runtime directory, neither existing yet") {
            `when`("the service starts and creates them") {
                then("config.json is 0600 and both directories are 0700") {
                    val temp = tempdir("stt-private").toPath()
                    val dirs =
                        ServiceDirs(
                            temp.resolve("config/stt-app"),
                            temp.resolve("runtime/stt-app"),
                        )
                    val service = startService(temp, freePort(), temp.resolve("service.log"), dirs)
                    try {
                        awaitServiceServing(readConfigPort(dirs), dirs) shouldBe true

                        permissions(configFile(dirs)) shouldBe "rw-------"
                        permissions(dirs.configDir) shouldBe "rwx------"
                        permissions(dirs.runtimeDir) shouldBe "rwx------"
                    } finally {
                        service.destroyForcibly().waitFor()
                    }
                }
            }
        }
    })

/** The port the running service advertises, once `runtime.json` exists. */
private suspend fun readConfigPort(dirs: ServiceDirs): Int {
    val deadline = System.nanoTime() + 60_000_000_000L
    while (System.nanoTime() < deadline) {
        if (runtimeFile(dirs).toFile().exists()) {
            return readRuntime(dirs)
                .getValue("endpoint")
                .toString()
                .substringAfterLast(':')
                .trim('"')
                .toInt()
        }
        kotlinx.coroutines.delay(100)
    }
    error("the service wrote no runtime.json")
}

private fun permissions(path: java.nio.file.Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
