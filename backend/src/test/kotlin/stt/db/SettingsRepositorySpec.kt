package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import stt.config.Device

/**
 * Roadmap item 32, cycle 1: the `settings` row — the settled fresh-database defaults (device auto,
 * threads unset, language auto, speakers mode auto, max speakers 8), storing a change, and the change
 * surviving a restart (a reopen of the same data directory).
 */
class SettingsRepositorySpec :
    BehaviorSpec({
        given("a settings repository on a fresh data directory") {
            `when`("the settings are read, changed, and the database is reopened") {
                then("a fresh database has the settled defaults, the change is stored, and a restart keeps it") {
                    runTest {
                        val dataDir = tempdir("stt-settings").toPath()
                        val defaults =
                            Settings(
                                device = Device.Auto,
                                threads = null,
                                defaultLanguage = "auto",
                                speakersMode = SpeakersMode.AUTO,
                                speakersCount = null,
                                defaultMaxSpeakers = 8,
                            )
                        val changed =
                            Settings(
                                device = Device.Gpu(2),
                                threads = 4,
                                defaultLanguage = "de",
                                speakersMode = SpeakersMode.OFF,
                                speakersCount = null,
                                defaultMaxSpeakers = 12,
                            )

                        Database.open(dataDir).use { db ->
                            val settings = SettingsRepository(db)
                            settings.get().shouldBe(defaults)
                            settings.update(changed)
                        }

                        // A restart: the same data directory is opened again, and the stored settings come back.
                        Database.open(dataDir).use { db ->
                            SettingsRepository(db).get().shouldBe(changed)
                        }
                    }
                }
            }
        }
    })