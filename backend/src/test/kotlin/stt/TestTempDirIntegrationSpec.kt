package stt

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import stt.db.ScratchDir
import java.nio.file.Path

/**
 * Item 77, test-list item "The app under test writes its temp files (its scratch directory) inside the watched temp
 * dir, not in the shared OS temp dir", the `gradle integrationTest` half ([TestTempDirSpec] is the `gradle test`
 * half): the integration JVM's `java.io.tmpdir` is its own task's private directory, and the app's [ScratchDir]
 * lives inside it.
 */
class TestTempDirIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("the integration test JVM's java.io.tmpdir and the shared OS temp dirs named by the environment") {
            `when`("the app derives its scratch directory for a data directory") {
                then("the scratch directory lies inside integrationTest's own java.io.tmpdir, none of the shared OS temp dirs") {
                    val appTemp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
                    val scratch = ScratchDir(tempdir("stt-data").toPath()).dir
                    val shared = sharedOsTempDirs()
                    check(shared.isNotEmpty()) { "no shared OS temp dir found to compare with" }

                    appTemp.fileName.toString() shouldBe "integrationTest"
                    scratch.parent.toRealPath() shouldBe appTemp
                    shared shouldNotContain appTemp
                }
            }
        }
    })
