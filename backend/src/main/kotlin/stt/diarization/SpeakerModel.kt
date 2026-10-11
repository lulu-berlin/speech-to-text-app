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
         * Session options. ONNX Runtime is force-pinned to 1.31.0 (roadmap item 30, ADR 0043): the 1.29.0 and 1.30.0
         * bundled natives both segfault on one user's machine (AMD Ryzen, 16 threads; SIGSEGV from a native
         * stack-overflow recursion in CPU-dispatched ORT code, no JVM hs_err — reproduced with every threading
         * and execution combination, so the crash cannot be configured away; 1.26.0-1.28.0 are rejected because
         * their CAM++ embeddings degrade everywhere). Execution stays sequential with a single inter-op thread
         * as defense-in-depth: ORT's parallel inter-op scheduler is never entered, which costs nothing on this
         * single-forward-pass model. Intra-op threads honor [intraOpThreads] when set, otherwise ONNX Runtime's
         * default. None of these settings has a read-back in onnxruntime's Java API (the option objects only
         * expose getNativeHandle and the config entries), so end-to-end acceptance is covered by the Integration
         * tests and the real-model runs on the user's machine.
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
