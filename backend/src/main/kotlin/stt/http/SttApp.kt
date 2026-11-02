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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import stt.config.Config
import stt.db.AudioRepository
import stt.db.CanonicalClipRepository
import stt.db.ChunkRepository
import stt.db.Database
import stt.db.QueueStateRepository
import stt.db.ScratchDir
import stt.db.SegmentRepository
import stt.db.SettingsRepository
import stt.db.TaskRepository
import stt.db.discardUnclaimedStaged
import stt.engine.processingEngine
import stt.process.ProcessRunner
import stt.transcribe.GpuDevice
import java.io.File
import java.nio.file.Path

/**
 * Application composition (ADR 0013): plugins are installed here, services are
 * registered with Ktor DI, feature modules are loaded. `dependencies {}` installs
 * the DI plugin itself, so a test may provide overrides before calling this.
 * The Database is required, so DI opens it at startup rather than on first use
 * (ADR 0017); DI closes it when the application stops. [gpus] are the GPUs the
 * startup device probe reported (item 32; `main` runs the probe), offered by `GET /api/settings`;
 * [deviceProbeError] is that probe's launch failure when it could not run (item 60). [credential] is the service's
 * per-install secret in app mode (roadmap item 66): every request must then carry it; null (dev/server mode) leaves the
 * app open.
 */
fun Application.sttApp(
    config: Config,
    gpus: List<GpuDevice> = emptyList(),
    deviceProbeError: String? = null,
    credential: String? = null,
    frontendsDir: Path? = null,
    quit: CompletableDeferred<Unit>? = null,
    registerTray: ((pid: Long, command: List<String>) -> Unit)? = null,
) {
    install(ContentNegotiation) { json() }
    val access = BrowserAccess()
    if (credential != null) requireCredential(credential, access)
    internalErrorHandler()
    // The events bridge runs for the application's lifetime (ADR 0007/0038): a scope under the app's job.
    val eventsScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]) + Dispatchers.Default)
    dependencies {
        // A staged original no task claimed is what a crash mid-upload leaves (item 44); DI opens the database
        // at startup, before any route serves, so none of these belongs to an upload still in flight.
        provide<Database> { Database.open(config.dataDir).also { it.discardUnclaimedStaged() } }
        provide<ScratchDir> { ScratchDir(config.dataDir) }
        provide<TaskRepository> { TaskRepository(resolve()) }
        provide<AudioRepository> { AudioRepository(resolve()) }
        provide<QueueStateRepository> { QueueStateRepository(resolve()) }
        provide<SettingsRepository> { SettingsRepository(resolve()) }
        provide<SegmentRepository> { SegmentRepository(resolve()) }
        provide<ChunkRepository> { ChunkRepository(resolve()) }
        provide<CanonicalClipRepository> { CanonicalClipRepository(resolve()) }
        provide<EventStream> { NumberedEventStream(resolve(), eventsScope) }
        require(DependencyKey<Database>())
    }
    settingsRoutes(gpus, deviceProbeError, frontendsDir)
    processingEngine(config)
    healthRoutes()
    taskRoutes()
    taskCommandRoutes()
    queueRoutes()
    transcriptRoutes()
    segmentEditRoutes()
    speakerRoutes()
    audioRoutes()
    eventsRoutes()
    uploadRoutes(config)
    // The browser's way in, app mode only (roadmap item 66): it needs the per-install credential, which is what the
    // tray and `stt-app open` have.
    if (credential != null) launchRoutes(credential, access)
    frontendsRoutes(frontendsDir)
    if (quit != null) quitRoutes(quit)
    // The tray registers itself so the service can restart it (ADR 0064); app mode only.
    if (registerTray != null) trayRoutes(registerTray)
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
