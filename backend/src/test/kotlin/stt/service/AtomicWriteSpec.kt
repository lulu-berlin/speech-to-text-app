package stt.service

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.listDirectoryEntries

/**
 * Roadmap item 66, test-list D12: a bootstrap file is written whole or not at all. The service writes a fresh file and
 * moves it into place, so the file keeps its old content until the new one is complete: a crash mid-write leaves the
 * previous `config.json` and its secret, never a truncated one.
 */
class AtomicWriteSpec :
    BehaviorSpec({
        given("a config.json the service has already written") {
            `when`("the service writes the config again") {
                then("the file is replaced, not rewritten in place, and no temporary file is left beside it") {
                    val dirs = ServiceDirs(tempdir("stt-atomic-config").toPath(), tempdir("stt-atomic-runtime").toPath())
                    Files.createDirectories(dirs.configDir)
                    ServiceInstance.writeConfig(dirs, ServiceConfig(28763, "first-secret"))
                    val before = identity(configFile(dirs))

                    ServiceInstance.writeConfig(dirs, ServiceConfig(28764, "second-secret"))

                    identity(configFile(dirs)) shouldNotBe before
                    dirs.configDir.listDirectoryEntries().map { it.fileName.toString() } shouldContainExactly
                        listOf("config.json")
                    readConfig(dirs).getValue("port").toString() shouldBe "28764"
                }
            }
        }
    })

/**
 * What identifies the file itself at [file]: its `fileKey` where the platform has one (POSIX), and
 * the creation time where it has none (Windows reports `null`), so a replaced file is told from an
 * in-place rewrite on every OS.
 */
private fun identity(file: java.nio.file.Path): Any? {
    val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
    return attributes.fileKey() ?: attributes.creationTime()
}
