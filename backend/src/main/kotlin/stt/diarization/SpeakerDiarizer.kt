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
 * The ONNX session is loaded on the first diarization, so the application starts without touching the model, and
 * reused for every task with the same intra-op thread count; a task started with another count from the settings
 * (item 32) closes it and loads it again. [close] releases it when the application stops.
 */
internal class SpeakerDiarizer(
    private val modelPath: Path,
    private val segments: SegmentRepository,
) : Diarizer,
    AutoCloseable {
    private val loadLock = Mutex()
    private var model: SpeakerModel? = null
    private var embeddings: Embeddings? = null

    /** The intra-op thread count [model] was loaded with. */
    private var loadedThreads: Int? = null

    override suspend fun diarize(
        task: StoredTask,
        gate: PauseGate,
        pcm: FloatArray,
        threads: Int?,
        onStage: (DiarizationStage) -> Unit,
        onProgress: (Double) -> Unit,
    ) {
        val taskSegments = segments.findByTask(task.id)
        onStage(DiarizationStage.EMBEDDING)
        onProgress(0.0)
        val vectors = embeddings(threads).embed(pcm, taskSegments, gate) { done, total -> onProgress(done.toDouble() / total) }
        onStage(DiarizationStage.CLUSTERING)
        onProgress(0.0)
        val k = if (task.task.speakersMode == SpeakersMode.FIXED) task.task.speakersCount else null
        val ids =
            speakerIds(taskSegments, vectors, k, task.task.maxSpeakers, gate) { done, total ->
                onProgress(done.toDouble() / total)
            }
        segments.storeSpeakers(task.id, ids)
    }

    private suspend fun embeddings(threads: Int?): Embeddings =
        loadLock.withLock {
            embeddings?.takeIf { loadedThreads == threads } ?: run {
                model?.close()
                val loaded = SpeakerModel.load(modelPath, threads)
                logger.info("Loaded speaker model {} (intra-op threads: {})", modelPath, threads ?: "default")
                model = loaded
                loadedThreads = threads
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
