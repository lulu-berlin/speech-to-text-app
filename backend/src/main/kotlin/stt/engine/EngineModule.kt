package stt.engine

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import stt.audio.AudioChunker
import stt.audio.AudioDecoder
import stt.audio.FfmpegWindowCutter
import stt.config.Config
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
import stt.diarization.SpeakerDiarizer
import stt.process.ProcessRunner
import stt.transcribe.ChunkTranscriber

/**
 * The processing engine's feature module (ADR 0013): registers the [ProcessingEngine] with Ktor DI over the real
 * ffmpeg, whisper-cli and CAM++ implementations, and starts its worker with the application. Needs the
 * [Database], [ScratchDir] and [TaskRepository] the application provides.
 */
fun Application.processingEngine(config: Config) {
    // The application-level scope (AGENTS.md): a SupervisorJob under the application's job, so one failed
    // child never takes the engine down.
    val engineJob = SupervisorJob(coroutineContext[Job])
    val engineScope = CoroutineScope(coroutineContext + engineJob + Dispatchers.Default)
    dependencies {
        // One runner for every external process: it is single-flight (ADR 0020) and is the engine's pause control.
        provide<ProcessRunner> { ProcessRunner() }
        provide<SpeakerDiarizer> { SpeakerDiarizer(config.speakerModel, SegmentRepository(resolve())) }
        provide<ProcessingEngine> {
            val db: Database = resolve()
            val runner: ProcessRunner = resolve()
            ProcessingEngine(
                scope = engineScope,
                config = config,
                tasks = resolve<TaskRepository>(),
                chunks = ChunkRepository(db),
                audio = resolve<AudioRepository>(),
                audioHeaders = AudioHeaderRepository(db),
                audioSeconds = AudioSecondRepository(db),
                clipAudio = CanonicalClipRepository(db),
                segments = SegmentRepository(db),
                queueState = QueueStateRepository(db),
                settings = resolve<SettingsRepository>(),
                scratchDir = resolve<ScratchDir>(),
                chunker = AudioChunker(runner),
                transcriber = ChunkTranscriber(runner),
                processControl = runner,
                diarizer = resolve<SpeakerDiarizer>(),
                pcmDecoder = AudioDecoder(runner),
                windowCutter = FfmpegWindowCutter(runner),
            )
        } cleanup {
            // The engine stops, and its in-flight database write and process kill finish, before DI closes the
            // Database it depends on. DI's cleanup is a blocking callback, hence the bridge.
            runBlocking { engineJob.cancelAndJoin() }
        }
    }
    // The engine is part of startup, like the Database (ADR 0017/0038): resolving it here creates it and
    // its cleanup exists before any stop can close the Database. DI's resolve is suspend; module loading
    // is not a coroutine, hence the same runBlocking bridge the cleanup uses.
    val engine: ProcessingEngine = runBlocking { dependencies.resolve<ProcessingEngine>() }
    // The worker starts on that same engine: it recovers what a crash left RUNNING, then takes the queue.
    launch { engine.start() }
}
