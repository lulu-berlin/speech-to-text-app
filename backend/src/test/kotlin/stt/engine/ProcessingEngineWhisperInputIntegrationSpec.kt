package stt.engine

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import stt.Integration
import stt.audio.AudioChunker
import stt.audio.AudioDecoder
import stt.audio.FfmpegWindowCutter
import stt.audio.generateNoise
import stt.audio.toS16
import stt.config.testConfig
import stt.db.AudioHeaderRepository
import stt.db.AudioRepository
import stt.db.AudioSecondRepository
import stt.db.CanonicalClipRepository
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.ScratchDir
import stt.db.SegmentRepository
import stt.db.SettingsRepository
import stt.db.TaskRepository
import stt.db.TestDataDir
import stt.db.queuedTask
import stt.process.ProcessRunner
import stt.queue.TaskStatus
import stt.transcribe.Transcriber
import stt.transcribe.TranscriptionException
import stt.transcribe.WhisperSegment
import stt.transcribe.WhisperSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * Item 44, test-list item "(discovered) Transcribing a chunk gives whisper exactly that chunk's samples from the
 * stored clip, in order (`[fromMs·16, toMs·16)`, the last chunk included); the temporary input is deleted after the
 * chunk, on success and on failure". The engine runs with the real chunker and window cutter (ffmpeg); only
 * whisper-cli is replaced, by a transcriber that decodes each input as it is handed over.
 */
class ProcessingEngineWhisperInputIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("11 min plus 7 samples of 16 kHz pink noise, silent around 289.95 s and 599.85 s, so windows start mid-second") {
            `when`("the engine chunks and transcribes it") {
                then("each chunk's whisper input is exactly that window's samples of the source, and no input survives") {
                    withEngineDatabase { dataDir, db ->
                        val fixtures = tempdir("stt-whisper-input").toPath()
                        val source = fixtures.resolve("source.wav")
                        val fixtureSamples = 11 * 60 * 16_000 + 7 // 10 560 007: not a whole millisecond, nor a frame
                        generateNoise(source, fixtureSamples, silences = listOf(289.3 to 290.6, 599.2 to 600.5))
                        // The source is 16 kHz mono s16, so the canonical clip is lossless: its samples are these.
                        val sourceSamples = toS16(source, fixtures)
                        sourceSamples.size shouldBe fixtureSamples * 2

                        val run = realAudioEngine(dataDir, db, fixtures)
                        val id = run.tasks.createFromUpload(queuedTask("noise.wav", createdAt = 1), Files.newInputStream(source))
                        run.engine.start()
                        // The status is written before endRun removes the run's scratch, so wait for both.
                        pumpUntil(3.minutes) {
                            statusOf(run.tasks, id) == TaskStatus.COMPLETED &&
                                filesUnder(run.scratchDir.taskDirPath(id)).isEmpty()
                        }

                        statusOf(run.tasks, id) shouldBe TaskStatus.COMPLETED
                        val chunks = ChunkRepository(db).findByTask(id)
                        // The fixture does what it claims: a window starts mid-second, and the last ends at the clip's
                        // last whole millisecond (10 560 007 / 16 = 660 000.4).
                        chunks.any { it.startMs % 1_000 != 0L } shouldBe true
                        chunks.last().endMs shouldBe 660_000L
                        run.transcriber.samples.size shouldBe chunks.size
                        chunks.zip(run.transcriber.samples).forEach { (chunk, input) ->
                            val expected = sourceSamples.copyOfRange((chunk.startMs * 32).toInt(), (chunk.endMs * 32).toInt())
                            // A mismatch names the chunk, both lengths and the first differing byte — not a diff of
                            // millions of bytes.
                            Triple(chunk.idx, input.size, firstDifference(input, expected)) shouldBe
                                Triple(chunk.idx, expected.size, null)
                        }
                        run.transcriber.inputs.forEach { Files.exists(it).shouldBeFalse() }
                        filesUnder(run.scratchDir.taskDirPath(id)).shouldBeEmpty()
                    }
                }
            }
        }

        given("a 3 s clip whose whisper run fails on its only chunk") {
            `when`("the engine transcribes it") {
                then("the task fails and its whisper input is deleted") {
                    withEngineDatabase { dataDir, db ->
                        val fixtures = tempdir("stt-whisper-input").toPath()
                        val source = fixtures.resolve("source.wav")
                        generateNoise(source, 3 * 16_000, silences = emptyList())
                        val run = realAudioEngine(dataDir, db, fixtures)
                        run.transcriber.failOnCall = 0
                        val id = run.tasks.createFromUpload(queuedTask("noise.wav", createdAt = 1), Files.newInputStream(source))
                        run.engine.start()
                        // FAILED is written before endRun removes the run's scratch, so wait for both.
                        pumpUntil(1.minutes) {
                            statusOf(run.tasks, id) == TaskStatus.FAILED &&
                                filesUnder(run.scratchDir.taskDirPath(id)).isEmpty()
                        }

                        statusOf(run.tasks, id) shouldBe TaskStatus.FAILED
                        run.transcriber.inputs.size shouldBe 1
                        Files.exists(run.transcriber.inputs.single()).shouldBeFalse()
                        filesUnder(run.scratchDir.taskDirPath(id)).shouldBeEmpty()
                    }
                }
            }
        }

        given("a 0.1 s clip of 16 kHz pink noise: 1 600 samples, less than one 4000-sample frame") {
            `when`("the engine chunks and transcribes it") {
                then("it completes with a one-frame clip of 100 ms stored, and whisper gets exactly its 1 600 samples") {
                    withEngineDatabase { dataDir, db ->
                        val fixtures = tempdir("stt-whisper-input").toPath()
                        val source = fixtures.resolve("source.wav")
                        generateNoise(source, 1_600, silences = emptyList())
                        val sourceSamples = toS16(source, fixtures)
                        val run = realAudioEngine(dataDir, db, fixtures)
                        val id = run.tasks.createFromUpload(queuedTask("short.wav", createdAt = 1), Files.newInputStream(source))
                        run.engine.start()
                        pumpUntil(1.minutes) { statusOf(run.tasks, id) in setOf(TaskStatus.COMPLETED, TaskStatus.FAILED) }

                        val task = run.tasks.find(id)?.task
                        (task?.status to task?.error) shouldBe (TaskStatus.COMPLETED to null)
                        // 1 600 samples / 16 per ms = 100 ms, in one window and one stored row of one frame.
                        task?.durationMs shouldBe 100L
                        ChunkRepository(db).findByTask(id).map { it.startMs to it.endMs } shouldBe listOf(0L to 100L)
                        AudioSecondRepository(db).read(id).map { it.second to it.frames } shouldBe listOf(0 to 1)
                        val input = run.transcriber.samples.single()
                        (input.size to firstDifference(input, sourceSamples)) shouldBe (sourceSamples.size to null)
                    }
                }
            }
        }
    })

/** The engine over the real chunker, decoder and window cutter, with a [DecodingTranscriber] for whisper-cli. */
private class RealAudioEngine(
    val tasks: TaskRepository,
    val scratchDir: ScratchDir,
    val transcriber: DecodingTranscriber,
    val engine: ProcessingEngine,
)

private fun TestScope.realAudioEngine(
    dataDir: TestDataDir,
    db: Database,
    fixtures: Path,
): RealAudioEngine {
    val runner = ProcessRunner()
    val scratchDir = ScratchDir(dataDir.dataDir)
    val tasks = TaskRepository(db)
    val transcriber = DecodingTranscriber(fixtures.resolve("inputs"))
    val engine =
        ProcessingEngine(
            scope = backgroundScope,
            config = testConfig(dataDir.dataDir),
            tasks = tasks,
            chunks = ChunkRepository(db),
            audio = AudioRepository(db),
            audioHeaders = AudioHeaderRepository(db),
            audioSeconds = AudioSecondRepository(db),
            clipAudio = CanonicalClipRepository(db),
            segments = SegmentRepository(db),
            queueState = QueueStateRepository(db),
            settings = SettingsRepository(db),
            scratchDir = scratchDir,
            chunker = AudioChunker(runner),
            transcriber = transcriber,
            processControl = runner,
            diarizer = FakeDiarizer(),
            pcmDecoder = AudioDecoder(runner),
            windowCutter = FfmpegWindowCutter(runner),
        )
    return RealAudioEngine(tasks, scratchDir, transcriber, engine)
}

/** Stands in for whisper-cli: decodes every input it is handed to raw s16 (the input is deleted after the chunk). */
private class DecodingTranscriber(
    private val scratch: Path,
) : Transcriber {
    /** Every input path, in call order. */
    val inputs = mutableListOf<Path>()

    /** Every input's samples as raw little-endian s16, in call order. */
    val samples = mutableListOf<ByteArray>()

    /** The call (0-based) that fails like a broken whisper-cli run; null for none. */
    var failOnCall: Int? = null

    override suspend fun transcribe(
        settings: WhisperSettings,
        audio: Path,
        outputPrefix: Path,
        prompt: String?,
        onProgress: (Double) -> Unit,
        onGpuUnavailable: suspend () -> Unit,
    ): List<WhisperSegment> {
        inputs.add(audio)
        samples.add(toS16(audio, scratch))
        if (inputs.size - 1 == failOnCall) throw TranscriptionException("fake whisper-cli failure on $audio")
        return emptyList()
    }
}

/** The first index where [a] and [b] differ, over their common length; null when that much of them is equal. */
private fun firstDifference(
    a: ByteArray,
    b: ByteArray,
): Int? = (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] }

/** The stored status of a task. */
private suspend fun statusOf(
    tasks: TaskRepository,
    id: Long,
): TaskStatus? = tasks.find(id)?.task?.status
