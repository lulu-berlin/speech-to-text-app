package stt.engine

import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.runTestApplication
import io.ktor.test.dispatcher.runTestWithRealTime
import stt.config.Config
import stt.config.realSpeakerModelPath
import stt.config.storeCpuDevice
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.http.sttApp
import stt.transcribe.transcriptFixtures
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes

/**
 * Runs [block] against the real app — the real ffmpeg, whisper-cli (ggml-tiny.en) and CAM++ model —
 * with a fresh data directory and the CPU device stored, so no GPU is probed.
 *
 * Ktor's `testApplication` caps its real-time run at 60 s, which a multi-chunk clip exceeds; this
 * harness uses `runTestWithRealTime` with a longer bound and starts the app exactly as
 * `testApplication` would. Specs resolve what they need from [ApplicationTestBuilder.application].
 */
internal suspend fun realEngineApp(block: suspend ApplicationTestBuilder.() -> Unit) {
    val (whisperModel, _) = transcriptFixtures()
    val dataDir = Files.createTempDirectory("stt-engine")
    val config = Config(dataDir, realSpeakerModelPath(), whisperModel)
    storeCpuDevice(dataDir)
    runTestWithRealTime(timeout = 10.minutes) {
        runTestApplication {
            application { sttApp(config) }
            startApplication()
            block()
        }
    }
}

/**
 * Queues [bytes] as [name] at position 0 with `createdAt = 0` (later tasks sort after it), the shape
 * every engine spec uploads through the repository.
 */
internal suspend fun TaskRepository.upload(
    name: String,
    bytes: ByteArray,
): Long = createFromUpload(queuedTask(name, createdAt = 0).copy(queuePosition = 0), ByteArrayInputStream(bytes))
