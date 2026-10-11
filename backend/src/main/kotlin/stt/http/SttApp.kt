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
import stt.audio.AudioJoiner
import stt.audio.FfmpegAudioJoiner
import stt.config.Config
import stt.db.AudioRepository
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.SegmentRepository
import stt.db.SettingsRepository
import stt.db.TaskRepository
import stt.db.TaskWorkDirectories
import stt.engine.processingEngine
import stt.process.ProcessRunner
import stt.transcribe.GpuDevice
import java.io.File

/**
 * Application composition (ADR 0013): plugins are installed here, services are
 * registered with Ktor DI, feature modules are loaded. `dependencies {}` installs
 * the DI plugin itself, so a test may provide overrides before calling this.
 * The Database is required, so DI opens it at startup rather than on first use
 * (ADR 0017); DI closes it when the application stops. [gpus] are the GPUs the
 * startup device probe reported (item 32; `main` runs the probe), offered by `GET /api/settings`.
 */
fun Application.sttApp(
    config: Config,
    gpus: List<GpuDevice> = emptyList(),
) {
    install(ContentNegotiation) { json() }
    internalErrorHandler()
    // The events bridge runs for the application's lifetime (ADR 0007/0038): a scope under the app's job.
    val eventsScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]) + Dispatchers.Default)
    dependencies {
        provide<Database> { Database.open(config.dataDir) }
        provide<TaskWorkDirectories> { TaskWorkDirectories(config.dataDir) }
        provide<TaskRepository> { TaskRepository(resolve(), resolve()) }
        provide<AudioRepository> { AudioRepository(resolve()) }
        provide<QueueStateRepository> { QueueStateRepository(resolve()) }
        provide<SettingsRepository> { SettingsRepository(resolve()) }
        provide<SegmentRepository> { SegmentRepository(resolve()) }
        provide<ChunkRepository> { ChunkRepository(resolve()) }
        provide<JoinedAudioCache> { JoinedAudioCache(resolve()) }
        provide<AudioJoiner> { FfmpegAudioJoiner(resolve(), resolve(), resolve()) }
        provide<EventStream> { NumberedEventStream(resolve(), eventsScope) }
        require(DependencyKey<Database>())
    }
    processingEngine(config)
    healthRoutes()
    taskRoutes()
    taskCommandRoutes()
    queueRoutes()
    transcriptRoutes()
    speakerRoutes()
    audioRoutes()
    eventsRoutes()
    uploadRoutes(config)
    settingsRoutes(gpus)
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
