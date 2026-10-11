package stt.diarization

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions.ExecutionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** The CAM++ speaker-embedding model (ADR 0003): one ONNX Runtime session on the CPU and the model's [metadata]. */
internal class SpeakerModel private constructor(
    internal val session: OrtSession,
    val metadata: SpeakerModelMetadata,
) : AutoCloseable {
    override fun close() = session.close()

    companion object {
        /**
         * Session options. Execution is sequential with a single inter-op thread (roadmap item 30): ORT's parallel
         * inter-op executor is never entered, which removes the infinite-recursion stack overflow of onnxruntime
         * 1.30.0's parallel path seen on the user's machine (AMD Ryzen, 16 threads; SIGSEGV, no JVM hs_err). Intra-op
         * threads honor [intraOpThreads] when set, otherwise ONNX Runtime's default. None of these settings has a
         * read-back in onnxruntime 1.30.0's Java API (the option objects only expose getNativeHandle and the config
         * entries), so end-to-end acceptance is covered by the Integration tests and the real-model runs on the
         * user's machine.
         */
        internal fun sessionOptions(intraOpThreads: Int? = null): OrtSession.SessionOptions =
            OrtSession.SessionOptions().apply {
                setExecutionMode(ExecutionMode.SEQUENTIAL)
                setInterOpNumThreads(1)
                if (intraOpThreads != null) setIntraOpNumThreads(intraOpThreads)
            }

        /** Creates the session from the model file at [path] (blocking file I/O, so on [Dispatchers.IO]) and reads its metadata. */
        suspend fun load(
            path: Path,
            intraOpThreads: Int? = null,
        ): SpeakerModel =
            withContext(Dispatchers.IO) {
                val session =
                    sessionOptions(intraOpThreads).use { options ->
                        OrtEnvironment.getEnvironment().createSession(path.toString(), options)
                    }
                try {
                    SpeakerModel(session, SpeakerModelMetadata.parse(session.metadata.customMetadata))
                } catch (e: Exception) {
                    session.close()
                    throw e
                }
            }
    }
}
