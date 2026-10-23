package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.server.plugins.di.dependencies
import kotlinx.coroutines.delay
import stt.Integration
import stt.db.Database
import stt.db.SegmentRepository
import stt.db.TaskPhase
import stt.db.TaskRepository
import stt.queue.TaskStatus
import stt.transcribe.transcriptFixtures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Roadmap item 5: a running task paused through the engine stops at the next chunk boundary and resume
 * continues from there without corrupting the transcript.
 *
 * The clip is whisper.cpp's 11 s jfk.wav looped 30 times (330 s), which the chunker splits into a 300 s
 * chunk and its 30 s tail. Nothing freezes the in-flight whisper-cli on Windows (no signals), so the
 * pause only stops the run before the next chunk; on a host with signals the in-flight process is frozen
 * too. Progress therefore must stop moving while paused, at a value below 1.0, and the resumed run must
 * complete with contiguous, increasing segments.
 */
class ProcessingEnginePauseIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("the application with the real ffmpeg, whisper-cli (ggml-tiny.en) and CAM++ model") {
            `when`("a task transcribing a two-chunk clip is paused and then resumed") {
                then("progress stops while PAUSED and the resumed run completes the transcript intact") {
                    val (_, jfk) = transcriptFixtures()
                    val clip = loopWav(jfk, loops = 30)
                    realEngineApp {
                        val db = application.dependencies.resolve<Database>()
                        val tasks = TaskRepository(db)
                        val segments = SegmentRepository(db)
                        val id = tasks.upload("long.wav", clip)
                        val engine = application.dependencies.resolve<ProcessingEngine>()
                        engine.wake()

                        awaitUntil(timeout = 2.minutes, every = 100.milliseconds) {
                            val task = tasks.find(id)?.task
                            task?.phase == TaskPhase.TRANSCRIBING && task.progress > 0.0
                        }
                        engine.pauseTask(id) shouldBe TaskCommandOutcome.Accepted
                        tasks.find(id)?.task?.status shouldBe TaskStatus.PAUSED

                        // The in-flight chunk finishes (or is frozen where signals exist), then the gate holds
                        // the run: progress must stop moving below completion. On Windows nothing freezes it, so
                        // wait until its whisper-cli has exited before reading a stable progress.
                        awaitUntil(timeout = 2.minutes, every = 100.milliseconds) { runningTools().isEmpty() }
                        val pausedAt = settledProgress(progress = { tasks.find(id)?.task?.progress ?: 1.0 })
                        (pausedAt > 0.0) shouldBe true
                        (pausedAt < 1.0) shouldBe true
                        tasks.find(id)?.task?.status shouldBe TaskStatus.PAUSED
                        tasks.find(id)?.task?.progress shouldBe pausedAt

                        engine.resumeTask(id) shouldBe TaskCommandOutcome.Accepted
                        awaitUntil(timeout = 5.minutes, every = 200.milliseconds) {
                            tasks.find(id)?.task?.status == TaskStatus.COMPLETED
                        }

                        val task = tasks.find(id)!!.task
                        (task.status to task.error) shouldBe (TaskStatus.COMPLETED to null)
                        task.progress shouldBe 1.0
                        val stored = segments.findByTask(id)
                        stored.shouldNotBeEmpty()
                        val transcript = stored.joinToString("") { it.text }.lowercase()
                        transcript shouldContain "ask not what your country can do for you"
                        // Chunks are stitched contiguously from the clip's start: no repeated or rewritten
                        // segment positions after the pause.
                        stored.map { it.startMs }.zipWithNext().forEach { (a, b) -> (a < b) shouldBe true }
                    }
                }
            }
        }
    })

/**
 * The task's progress once it has not changed for [stableFor]; the last value when [timeout] passes.
 * The engine persists one value per whisper progress report, so a paused run settles as soon as the
 * in-flight chunk ends (immediately where its process is frozen).
 */
private suspend fun settledProgress(
    progress: suspend () -> Double,
    stableFor: Duration = 3.seconds,
    timeout: Duration = 5.minutes,
): Double {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    var last = Double.NaN
    var since = TimeSource.Monotonic.markNow()
    while (deadline.hasNotPassedNow()) {
        val now = progress()
        if (now != last) {
            last = now
            since = TimeSource.Monotonic.markNow()
        } else if (since.elapsedNow() >= stableFor) {
            return now
        }
        delay(150.milliseconds)
    }
    return last
}

/** The fixture WAV with its data chunk repeated [loops] times, as a minimal RIFF/WAVE header and payload. */
internal fun loopWav(
    source: Path,
    loops: Int,
): ByteArray {
    val bytes = Files.readAllBytes(source)
    require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
    var fmt: ByteArray? = null
    var data: ByteArray? = null
    var at = 12
    while (at + 8 <= bytes.size) {
        val id = String(bytes, at, 4, Charsets.US_ASCII)
        val size = ByteBuffer.wrap(bytes, at + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        when (id) {
            "fmt " -> fmt = bytes.copyOfRange(at + 8, at + 8 + size)
            "data" -> data = bytes.copyOfRange(at + 8, at + 8 + size)
        }
        at += 8 + size + (size and 1)
    }
    val format = requireNotNull(fmt) { "the fixture has no fmt chunk" }
    val audio = requireNotNull(data) { "the fixture has no data chunk" }
    val payload = audio.size * loops
    val out = ByteBuffer.allocate(12 + 8 + format.size + 8 + payload).order(ByteOrder.LITTLE_ENDIAN)
    out.put("RIFF".toByteArray(Charsets.US_ASCII))
    out.putInt(4 + 8 + format.size + 8 + payload)
    out.put("WAVE".toByteArray(Charsets.US_ASCII))
    out.put("fmt ".toByteArray(Charsets.US_ASCII))
    out.putInt(format.size)
    out.put(format)
    out.put("data".toByteArray(Charsets.US_ASCII))
    out.putInt(payload)
    repeat(loops) { out.put(audio) }
    return out.array()
}
