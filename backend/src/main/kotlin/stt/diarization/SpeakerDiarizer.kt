package stt.diarization

import kotlinx.coroutines.runBlocking
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
        audio: PcmSource,
        threads: Int?,
        onStage: (DiarizationStage) -> Unit,
        onProgress: (Double) -> Unit,
    ) {
        val taskSegments = segments.findByTask(task.id)
        onStage(DiarizationStage.EMBEDDING)
        onProgress(0.0)
        val vectors = embeddings(threads).embed(audio, taskSegments, gate) { done, total -> onProgress(done.toDouble() / total) }
        onStage(DiarizationStage.CLUSTERING)
        onProgress(0.0)
        val k = if (task.task.speakersMode == SpeakersMode.FIXED) task.task.speakersCount else null
        val ids =
            speakerIds(taskSegments, vectors, k, task.task.maxSpeakers, gate) { done, total ->
                onProgress(done.toDouble() / total)
            }
        segments.storeSpeakers(task.id, ids)
    }

    /** The embeddings for [threads], loading or reloading the model; internal so the reload spec can drive it (item 95). */
    internal suspend fun embeddings(threads: Int?): Embeddings =
        loadLock.withLock {
            embeddings?.takeIf { loadedThreads == threads } ?: run {
                // Load before closing the old model (item 95): a failed load must leave the working session cached,
                // so the next task at the old thread count diarizes instead of touching a closed session.
                val loaded = SpeakerModel.load(modelPath, threads)
                val ready =
                    try {
                        Embeddings(loaded)
                    } catch (e: Exception) {
                        loaded.close()
                        throw e
                    }
                model?.close()
                model = loaded
                loadedThreads = threads
                embeddings = ready
                logger.info("Loaded speaker model {} (intra-op threads: {})", modelPath, threads ?: "default")
                ready
            }
        }

    override fun close() {
        // The application's cleanup may race a load or an embedding; the lock serializes it (item 95).
        runBlocking {
            loadLock.withLock {
                model?.close()
                model = null
                embeddings = null
                loadedThreads = null
            }
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SpeakerDiarizer::class.java)
    }
}
