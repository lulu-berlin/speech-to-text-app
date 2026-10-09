package stt

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import stt.config.Config
import stt.config.TEST_DATA_DIR
import stt.config.TEST_SPEAKER_MODEL
import java.nio.file.Path

class MainSpec :
    BehaviorSpec({

        given("a configuration without STT_STATIC_DIR") {
            `when`("checking the boot warning") {
                then("a warning naming STT_STATIC_DIR is logged, none when it is set") {
                    withCapturedLogs("stt.Main") { events ->
                        warnIfStaticDirUnset(Config(dataDir = Path.of(TEST_DATA_DIR), speakerModel = Path.of(TEST_SPEAKER_MODEL)))
                        warnIfStaticDirUnset(
                            Config(
                                dataDir = Path.of(TEST_DATA_DIR),
                                speakerModel = Path.of(TEST_SPEAKER_MODEL),
                                staticDir = Path.of("/some/dir"),
                            ),
                        )
                        val warnings = events.filter { it.level == Level.WARN }
                        warnings.size.shouldBe(1)
                        warnings.single().formattedMessage.shouldContain("STT_STATIC_DIR")
                    }
                }
            }
        }
    })
