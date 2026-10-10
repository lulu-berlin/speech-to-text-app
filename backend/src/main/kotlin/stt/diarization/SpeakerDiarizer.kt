package stt.diarization

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import stt.db.DiarizationStage
import stt.db.SegmentRepository
import stt.db.SpeakersMode
import stt.db.StoredTask
import stt.engine.Diarizer
import stt.pause.PauseGate
import java.nio.file.Path

/**
 * The engine's [Diarizer] (roadmap item 11) over items 08/09: embeds every segment of the task with the CAM++ model
 * ([Embeddings], stage `EMBEDDING`), labels them ([speakerIds], stage `CLUSTERING`) and stores the speakers. Each
 * stage reports 0 when it starts and then `done / total` after every window or merge.
 *
 * The ONNX session is created once per application run (the application holds one instance) and reused for every
 * task. It is loaded on the first diarization, so the application starts without touching the model;
 * [close] releases it when the application stops.
 */
internal class SpeakerDiarizer(
    private val modelPath: Path,
    private val intraOpThreads: Int?,
    private val segments: SegmentRepository,
) : Diarizer,
    AutoCloseable {
    private val loadLock = Mutex()
    private var model: SpeakerModel? = null
    private var embeddings: Embeddings? = null

    override suspend fun diarize(
        task: StoredTask,
        gate: PauseGate,
        pcm: FloatArray,
        onStage: (DiarizationStage) -> Unit,
        onProgress: (Double) -> Unit,
    ) {
        val taskSegments = segments.findByTask(task.id)
        onStage(DiarizationStage.EMBEDDING)
        onProgress(0.0)
        val vectors = embeddings().embed(pcm, taskSegments, gate) { done, total -> onProgress(done.toDouble() / total) }
        onStage(DiarizationStage.CLUSTERING)
        onProgress(0.0)
        val k = if (task.task.speakersMode == SpeakersMode.FIXED) task.task.speakersCount else null
        val ids =
            speakerIds(taskSegments, vectors, k, task.task.maxSpeakers, gate) { done, total ->
                onProgress(done.toDouble() / total)
            }
        segments.storeSpeakers(task.id, ids)
    }

    private suspend fun embeddings(): Embeddings =
        loadLock.withLock {
            embeddings ?: run {
                val loaded = SpeakerModel.load(modelPath, intraOpThreads)
                logger.info("Loaded speaker model {}", modelPath)
                model = loaded
                Embeddings(loaded).also { embeddings = it }
            }
        }

    override fun close() {
        model?.close()
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SpeakerDiarizer::class.java)
    }
}
