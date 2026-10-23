package stt

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import stt.db.ScratchDir
import java.nio.file.Path

/**
 * Item 77, test-list item "The app under test writes its temp files (its scratch directory) inside the watched temp
 * dir, not in the shared OS temp dir": the test JVM's `java.io.tmpdir`, which `UploadStoredInDatabaseSpec` watches
 * whole, is private to the test run, and the app's [ScratchDir] lives inside it.
 */
class TestTempDirSpec :
    BehaviorSpec({
        given("the test JVM's java.io.tmpdir and the shared OS temp dirs named by the environment") {
            `when`("the app derives its scratch directory for a data directory") {
                then("the scratch directory lies inside java.io.tmpdir, which is none of the shared OS temp dirs") {
                    val appTemp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
                    val scratch = ScratchDir(tempdir("stt-data").toPath()).dir
                    val shared = sharedOsTempDirs()
                    check(shared.isNotEmpty()) { "no shared OS temp dir found to compare with" }

                    appTemp.fileName.toString() shouldBe "test"
                    scratch.parent.toRealPath() shouldBe appTemp
                    shared shouldNotContain appTemp
                }
            }
        }
    })
