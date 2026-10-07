package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class TaskWorkDirectoriesSpec :
    BehaviorSpec({

        given("the data directory /srv/stt-data") {
            `when`("asking for the work directory and the original of task 42") {
                then("the work directory is /srv/stt-data/work/42 and the original is /srv/stt-data/work/42/source") {
                    val workDirectories = TaskWorkDirectories(Path.of("/srv/stt-data"))

                    workDirectories.workDir(42L).shouldBe(Path.of("/srv/stt-data/work/42"))
                    workDirectories.source(42L).shouldBe(Path.of("/srv/stt-data/work/42/source"))
                }
            }
        }
    })
