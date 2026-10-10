package stt.http

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.di.DependencyKey
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.path
import io.ktor.server.response.respondFile
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import stt.audio.AudioChunker
import stt.audio.AudioDecoder
import stt.config.Config
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.SegmentRepository
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.diarization.SpeakerDiarizer
import stt.engine.ProcessingEngine
import stt.process.ProcessRunner
import stt.transcribe.ChunkTranscriber
import java.io.File

/**
 * Application composition (ADR 0013): plugins are installed here, services are
 * registered with Ktor DI, feature modules are loaded. `dependencies {}` installs
 * the DI plugin itself, so a test may provide overrides before calling this.
 * The Database is required, so DI opens it at startup rather than on first use
 * (ADR 0017); DI closes it when the application stops.
 */
fun Application.sttApp(config: Config) {
    install(ContentNegotiation) { json() }
    // The application-level scope (AGENTS.md): a SupervisorJob under the application's job, so one failed
    // child never takes the engine down.
    val engineJob = SupervisorJob(coroutineContext[Job])
    val appScope = CoroutineScope(coroutineContext + engineJob + Dispatchers.Default)
    dependencies {
        provide<Database> { Database.open(config.dataDir) }
        provide<TaskWorkDirectories> { TaskWorkDirectories(config.dataDir) }
        // One runner for every external process: it is single-flight (ADR 0020) and is the engine's pause control.
        provide<ProcessRunner> { ProcessRunner() }
        provide<SpeakerDiarizer> { SpeakerDiarizer(config.speakerModel, config.threads, SegmentRepository(resolve())) }
        provide<ProcessingEngine> {
            val db: Database = resolve()
            val workDirectories: TaskWorkDirectories = resolve()
            val runner: ProcessRunner = resolve()
            ProcessingEngine(
                scope = appScope,
                config = config,
                tasks = TaskRepository(db, workDirectories),
                chunks = ChunkRepository(db),
                segments = SegmentRepository(db),
                queueState = QueueStateRepository(db),
                workDirectories = workDirectories,
                chunker = AudioChunker(runner),
                transcriber = ChunkTranscriber(runner),
                processControl = runner,
                diarizer = resolve<SpeakerDiarizer>(),
                pcmDecoder = AudioDecoder(runner),
            )
        } cleanup {
            // The engine stops, and its in-flight database write and process kill finish, before DI closes the
            // Database it depends on. DI's cleanup is a blocking callback, hence the bridge.
            runBlocking { engineJob.cancelAndJoin() }
        }
        require(DependencyKey<Database>())
    }
    // The worker runs from startup: it recovers what a crash left RUNNING, then takes the queue.
    launch { dependencies.resolve<ProcessingEngine>().start() }
    healthRoutes()
    val staticDir = config.staticDir
    routing {
        if (staticDir != null) {
            staticFiles("/", staticDir.toFile()) {
                // /api is API-only: the SPA fallback must never serve index.html there (cycle 4).
                // Only /api and /api/... are excluded — /apiary-style prefixes must keep the
                // SPA fallback (review fix).
                filter { call ->
                    val path = call.request.path()
                    path == "/api" || path.startsWith("/api/")
                }
                fallback { _, call ->
                    call.respondFile(File(staticDir.toFile(), "index.html"))
                }
            }
        }
    }
}
