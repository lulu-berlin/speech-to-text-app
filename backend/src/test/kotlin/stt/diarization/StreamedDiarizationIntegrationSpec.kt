package stt.diarization

import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import stt.Integration
import stt.audio.AudioChunker
import stt.audio.AudioDecoder
import stt.audio.FFMPEG
import stt.audio.SAMPLES_PER_MS
import stt.audio.expectSuccess
import stt.audio.toCanonicalRows
import stt.audio.toS16
import stt.config.realSpeakerModelPath
import stt.db.CanonicalClipRepository
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.ScratchDir
import stt.db.Segment
import stt.db.SegmentRepository
import stt.db.StoredTask
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.db.withDatabase
import stt.engine.ClipPcmSource
import stt.pause.PauseGate
import stt.process.ProcessRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Item 43: the speaker windows a diarization reads out of a task's stored canonical clip (item 44) — as ranges of
 * the clip's samples ([ClipPcmSource]) — instead of out of a whole-clip `FloatArray`.
 *
 * The recording is the item 08 LibriSpeech material (docs/attributions.md): speakers 1089, 1188 and 1089
 * concatenated, with each clip split into two segments. Every clip boundary — and so every segment boundary —
 * falls inside one of the clip's stored one-second rows, so the windows start and end mid-row and span several of
 * them.
 *
 * The first scenario (test-list item 1) diarizes the recording with the real model through the streamed source
 * and checks the stored assignments, timestamps and turns; its expected values come from the fixture's known
 * speakers, as in [LibriSpeechDiarizationSpec]. The second (discovered) checks the source itself: every segment's
 * streamed window holds exactly the samples the whole clip's PCM holds at those positions.
 */
class StreamedDiarizationIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a recording of the LibriSpeech clips of speakers 1089, 1188 and 1089, stored as a canonical clip") {
            `when`("it is diarized with the real model, streaming its speaker windows from the stored clip") {
                then(
                    "the assignments are [0, 0, 1, 1, 0, 0], the segments keep their timestamps, and the turns change at the clip boundaries",
                ) {
                    withDatabase { db ->
                        val work = tempdir("streamed-diarization").toPath()
                        val runner = ProcessRunner()
                        val recording = storedRecording(db, work, runner)
                        val source =
                            ClipPcmSource(
                                taskId = recording.taskId,
                                totalSamples = recording.totalSamples,
                                clipAudio = CanonicalClipRepository(db),
                                pcmDecoder = AudioDecoder(runner),
                                scratchDir = ScratchDir(dataDir),
                            )
                        val segments = SegmentRepository(db)
                        val stored = StoredTask(recording.taskId, TaskRepository(db).find(recording.taskId)!!.task)
                        SpeakerDiarizer(realSpeakerModelPath(), segments).use { diarizer ->
                            diarizer.diarize(stored, PauseGate(), source, threads = null, onStage = {}, onProgress = {})
                        }

                        val diarized = segments.findByTask(recording.taskId)
                        diarized.map { it.speakerId } shouldBe listOf(0, 0, 1, 1, 0, 0)
                        diarized.map { it.startMs to it.endMs } shouldBe recording.segments.map { it.startMs to it.endMs }
                        speakerTurns(diarized, segments.findSpeakers(recording.taskId))
                            .map { Triple(it.speaker, it.startMs, it.endMs) } shouldBe
                            listOf(
                                Triple("Speaker 1", recording.boundariesMs[0], recording.boundariesMs[1]),
                                Triple("Speaker 2", recording.boundariesMs[1], recording.boundariesMs[2]),
                                Triple("Speaker 1", recording.boundariesMs[2], recording.boundariesMs[3]),
                            )
                    }
                }
            }
        }

        given("a recording whose segment boundaries fall inside the stored clip's one-second rows") {
            `when`("every segment's window is streamed from the stored clip") {
                then("each window holds exactly the samples the whole clip's PCM holds at those positions") {
                    withDatabase { db ->
                        val work = tempdir("streamed-windows").toPath()
                        val runner = ProcessRunner()
                        val recording = storedRecording(db, work, runner)
                        // The fixture does what it claims: five of the six segments start inside a stored row, and
                        // every window is a slice of the clip, never zero padding.
                        recording.segments.count { it.startMs % 1_000 != 0L } shouldBe 5
                        recording.segments.forEach { (it.endMs - it.startMs) shouldBeGreaterThan 2_000L }
                        val source =
                            ClipPcmSource(
                                taskId = recording.taskId,
                                totalSamples = recording.totalSamples,
                                clipAudio = CanonicalClipRepository(db),
                                pcmDecoder = AudioDecoder(runner),
                                scratchDir = ScratchDir(dataDir),
                            )
                        val wholeClip = pcmSource(recording.pcm)

                        recording.segments.forEach { segment ->
                            withClue("segment ${segment.startMs}..${segment.endMs} ms") {
                                shouldHoldTheSameSamples(
                                    embeddingWindow(source, segment.startMs, segment.endMs),
                                    embeddingWindow(wholeClip, segment.startMs, segment.endMs),
                                )
                            }
                        }
                    }
                }
            }
        }
    })

/** The item 08 LibriSpeech clips the recording is made of (docs/attributions.md), in order. */
private val RECORDING_IDS = listOf("1089-134686-0002", "1188-133604-0025", "1089-134691-0023")

/** The stored recording a streamed-diarization scenario diarizes, and the fixture facts its assertions use. */
private class StoredRecording(
    val taskId: Long,
    val totalSamples: Int,
    /** The recording's samples, as the canonical clip holds them (the concatenation is lossless). */
    val pcm: FloatArray,
    /** The clips' boundaries in ms: clip `i` spans `[boundariesMs[i], boundariesMs[i + 1])`. */
    val boundariesMs: List<Long>,
    /** The task's six segments: each clip's two halves, meeting at its midpoint. */
    val segments: List<Segment>,
)

/**
 * Stores what the scenarios diarize: [RECORDING_IDS] concatenated into one 16 kHz mono recording, chunked by the
 * real chunker so the task holds exactly the canonical clip the app keeps (item 44), and split into two segments
 * per clip. The concatenation is lossless, so the clip's samples are the clips' own, and the clip boundaries fall
 * inside the stored one-second rows.
 */
private suspend fun storedRecording(
    db: Database,
    work: Path,
    runner: ProcessRunner,
): StoredRecording {
    val clips = librispeechClips()
    // The fixture: the clips' own samples, the recording's sample count and its cumulative boundaries in ms.
    val clipBytes = RECORDING_IDS.map { toS16(clips.resolve("$it.flac"), work) }
    val totalSamples = clipBytes.sumOf { it.size } / 2
    val boundariesMs = clipBytes.runningFold(0L) { offset, bytes -> offset + bytes.size / 2 }.map { it / SAMPLES_PER_MS }

    val recording = work.resolve("recording.wav")
    val concat =
        FFMPEG + listOf("-y", "-loglevel", "error") +
            RECORDING_IDS.flatMap { listOf("-i", clips.resolve("$it.flac").toString()) } +
            listOf(
                "-filter_complex",
                "concat=n=${RECORDING_IDS.size}:v=0:a=1",
                "-ar",
                "16000",
                "-ac",
                "1",
                "-c:a",
                "pcm_s16le",
                recording.toString(),
            )
    runner.run(concat).expectSuccess("concat the LibriSpeech clips")
    // The concat is lossless and holds exactly the three clips: the fixture is what it claims.
    (toS16(recording, work).size / 2) shouldBe totalSamples

    val chunking = work.resolve("chunking")
    Files.createDirectories(chunking)
    val chunked = AudioChunker(runner).chunk(recording, chunking)
    chunked.durationMs shouldBe totalSamples.toLong() / SAMPLES_PER_MS
    val tasks = TaskRepository(db)
    val taskId = tasks.createFromUpload(queuedTask("recording.wav", createdAt = 1), Files.newInputStream(recording))
    ChunkRepository(db).storeChunked(taskId, chunked.chunks, chunked.canonical.header, chunked.canonical.toCanonicalRows())

    // Each clip is split into two segments meeting at its midpoint: six segments whose windows start and end
    // mid-row and span several of the clip's one-second rows.
    val segments =
        boundariesMs
            .zipWithNext()
            .flatMap { (startMs, endMs) ->
                val midMs = (startMs + endMs) / 2
                listOf(startMs to midMs, midMs to endMs)
            }.mapIndexed { seq, (startMs, endMs) ->
                Segment(chunkIdx = 0, seq = seq, startMs = startMs, endMs = endMs, text = "", speakerId = null)
            }
    SegmentRepository(db).insertAll(taskId, segments)
    return StoredRecording(taskId, totalSamples, s16Floats(clipBytes), boundariesMs, segments)
}

/** Asserts [samples] holds [expected] exactly, naming the first sample that differs. */
private fun shouldHoldTheSameSamples(
    samples: FloatArray,
    expected: FloatArray,
) {
    samples.size shouldBe expected.size
    val first = expected.indices.firstOrNull { samples[it] != expected[it] } ?: return
    fail("sample $first of ${expected.size} is ${samples[first]}, expected ${expected[first]}")
}

/** The 16 kHz mono s16 [clips] as the app's decoder delivers them: little-endian s16 scaled to `[-1, 1]`. */
private fun s16Floats(clips: List<ByteArray>): FloatArray {
    val bytes = ByteBuffer.allocate(clips.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
    clips.forEach { bytes.put(it) }
    bytes.flip()
    val samples = bytes.asShortBuffer()
    return FloatArray(samples.remaining()) { samples.get() / 32768f }
}
