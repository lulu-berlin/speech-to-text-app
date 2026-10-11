package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import stt.config.Device
import stt.db.Settings
import stt.db.SettingsRepository
import stt.db.SpeakersMode
import stt.transcribe.WhisperSegment

/**
 * Roadmap item 32, cycle 4: the engine takes the device and thread count from the stored settings when it starts a
 * task — for every whisper-cli run of that task and for the speaker model — so a change applies to the next task,
 * and a running task keeps the device it started with.
 */
class ProcessingEngineSettingsSpec :
    BehaviorSpec({
        given("settings stored as GPU 1 with 6 threads, and two queued two-chunk tasks with speakers auto") {
            `when`("the settings change to CPU with 2 threads while the first task transcribes its first chunk") {
                then("both chunks and the diarization of the first task use GPU 1 and 6 threads; the second task uses CPU and 2") {
                    withEngineDatabase { dataDir, db ->
                        val fixture = engineUnderTest(dataDir, db)
                        val settings = SettingsRepository(db)
                        val defaults = settings.get()
                        settings.update(defaults.copy(device = Device.Gpu(1), threads = 6))
                        val first =
                            enqueueTask(fixture.tasks, fixture.workDirectories, "first.wav", 0, 1, speakersMode = SpeakersMode.AUTO)
                        val second =
                            enqueueTask(fixture.tasks, fixture.workDirectories, "second.wav", 1, 2, speakersMode = SpeakersMode.AUTO)
                        fixture.chunker.chunkCount = 2
                        val hello = listOf(WhisperSegment(startMs = 0, endMs = 1_000, text = " hello "))
                        fixture.transcriber.segmentsByTask = mapOf(first to hello, second to hello)
                        val release = CompletableDeferred<Unit>()
                        fixture.transcriber.hold = release

                        fixture.engine.start()
                        taskIdOf(
                            fixture.transcriber.entered
                                .receive()
                                .parent,
                        ) shouldBe first
                        settings.update(Settings(Device.Cpu, 2, defaults.defaultLanguage, defaults.speakersMode, null, 8))
                        release.complete(Unit)

                        fixture.completed.receive() shouldBe first
                        fixture.completed.receive() shouldBe second
                        // By hand: two whisper-cli runs per task, in task order; one diarization per task.
                        fixture.transcriber.settings.map { it.device to it.threads } shouldBe
                            listOf(Device.Gpu(1) to 6, Device.Gpu(1) to 6, Device.Cpu to 2, Device.Cpu to 2)
                        fixture.diarizer.threads shouldBe listOf(6, 2)
                    }
                }
            }
        }
    })
