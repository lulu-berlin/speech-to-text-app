// Root Gradle build for human developers without Nix (roadmap item 33). The version
// manifest (versions.json) is the single source for every pinned version and
// download (`checkVersions` fails when it drifts from the wrapper or the locked
// nixpkgs - cycle 1). `run` builds the backend (composite build) and the frontend
// (node-plugin subproject), fetches and sha256-verifies the runtime tools and models
// into the Gradle user home, prepends the managed tools' directories to PATH
// (ADR 0047: no STT_WHISPER_CLI/STT_FFMPEG) and starts the backend with the STT_*
// environment resolved from versions.json.
plugins {
    // java-base only: gives the root build the javaToolchains service (the JDK-21
    // runtime resolution) without java compilation tasks.
    id("java-base")
}

import groovy.json.JsonSlurper
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

val manifest = JsonSlurper().parse(rootProject.file("versions.json")) as Map<*, *>

tasks.register("checkVersions") {
    group = "verification"
    description = "Fail when the committed versions.json drifts from the Gradle wrapper or the locked nixpkgs (roadmap item 33)"
    doLast {
        // 1. The wrapper's Gradle version must match the manifest (both are committed copies).
        val wrapperProperties = Properties()
        rootProject.file("gradle/wrapper/gradle-wrapper.properties").inputStream().use(wrapperProperties::load)
        val distributionUrl = wrapperProperties.getProperty("distributionUrl")
        val wrapperVersion = Regex("gradle-([^/]+)-bin\\.zip").find(distributionUrl)?.groupValues?.get(1)
            ?: throw GradleException("Cannot parse the Gradle version from distributionUrl: $distributionUrl")
        val manifestGradle = manifest["gradle"] as String
        if (wrapperVersion != manifestGradle) {
            throw GradleException(
                "versions.json pins Gradle $manifestGradle but the wrapper (gradle/wrapper/gradle-wrapper.properties) is $wrapperVersion. " +
                    "Run 'gradle wrapper --gradle-version $manifestGradle' at the repository root and commit the result."
            )
        }

        // 2. The manifest must be generated from the nixpkgs rev flake.lock pins; a
        //    nixpkgs bump changes every nixpkgs-derived version at once.
        val lock = JsonSlurper().parse(rootProject.file("flake.lock")) as Map<*, *>
        val lockRev = (((lock["nodes"] as Map<*, *>)["nixpkgs"] as Map<*, *>)["locked"] as Map<*, *>)["rev"] as String
        val manifestRev = manifest["nixpkgsRev"] as String
        if (lockRev != manifestRev) {
            throw GradleException(
                "versions.json was generated from nixpkgs ${manifestRev.take(12)} but flake.lock pins ${lockRev.take(12)}. " +
                    "On a machine with Nix, run 'nix run .#update-versions' at the repository root and commit the result."
            )
        }

        logger.lifecycle("checkVersions: versions.json is in sync (Gradle $manifestGradle, nixpkgs ${lockRev.take(12)})")
    }
}

// ---- platform: Linux x64 supported, macOS best-effort (no testing) ----
val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
val osArch = System.getProperty("os.arch").lowercase(Locale.ROOT)
val platform = when {
    osName.contains("mac") && osArch == "aarch64" -> "darwin-arm64"
    osName.contains("mac") -> "darwin-x64"
    osName.contains("linux") && (osArch == "amd64" || osArch == "x86_64") -> "linux-x64"
    else -> throw GradleException("Unsupported platform for ./gradlew run: $osName/$osArch (Linux x64 supported; macOS best-effort)")
}

// Downloads are cached under the Gradle user home, so a second clone reuses them.
val sttCache = File(
    System.getenv("GRADLE_USER_HOME") ?: File(System.getProperty("user.home"), ".gradle").absolutePath,
    "stt-app",
)

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

// Ensures target holds the bytes versions.json pins for url. A cached file is checked
// on every run (about 0.5 s of hashing for all tools and models): if it matches it is
// used without any network access (item 33: a second run downloads nothing), if not
// (a damaged or tampered cache) it is downloaded again.
fun downloadVerified(url: String, expectedSha256: String, target: File) {
    if (target.isFile) {
        val cached = sha256(target)
        if (cached == expectedSha256) return
        logger.warn("Cached ${target.name} does not match versions.json (sha256 $cached, expected $expectedSha256); downloading it again")
        target.delete()
    }
    logger.lifecycle("Downloading $url")
    target.parentFile.mkdirs()
    val tmp = File.createTempFile("stt-download", ".part", target.parentFile)
    try {
        try {
            URI(url).toURL().openStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        } catch (e: FileNotFoundException) {
            // HttpURLConnection reports 404/410 this way: the pinned asset was removed upstream.
            throw GradleException(
                "Download no longer exists upstream (HTTP 404): $url. Nothing is missing on this machine: " +
                    "the pin in versions.json must move to an available release (nix/versions.nix, then " +
                    "'nix run .#update-versions').",
                e,
            )
        }
        val actual = sha256(tmp)
        if (actual != expectedSha256) {
            throw GradleException("SHA-256 mismatch for $url: manifest says $expectedSha256, downloaded $actual")
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}

fun findExecutable(root: File, name: String): File {
    val exe = root.walkTopDown().filter { it.isFile && it.name == name }.minByOrNull { it.path.length }
        ?: throw GradleException("$name not found under $root")
    try {
        exe.setExecutable(true, false)
    } catch (_: Exception) {
        // non-POSIX or read-only fs: the run fails loudly later if it matters
    }
    return exe
}

fun defaultDataDir(): String {
    val xdg = System.getenv("XDG_DATA_HOME")
    val home = System.getenv("HOME")
    val base = if (!xdg.isNullOrEmpty()) xdg else (home ?: "") + "/.local/share"
    return "$base/stt-app"
}

val downloads = manifest["downloads"] as Map<*, *>
val whisperCliEntry = (downloads["whisper-cli"] as Map<*, *>)[platform] as Map<*, *>?
val ffmpegEntry = (downloads["ffmpeg"] as Map<*, *>)[platform] as Map<*, *>?
val models = manifest["models"] as Map<*, *>

// prepareRuntime writes this; the run, dev and e2e tasks read it at execution time.
val runtimeBinDirs = mutableListOf<File>()

// Downloads, verifies and unpacks one tool and returns its executable. Archives are
// .tar.gz (whisper-cli) or .jar (ffmpeg from Maven Central, a zip).
fun prepareTool(label: String, entry: Map<*, *>, exeName: String): File {
    val url = entry["url"] as String
    val sha = entry["sha256"] as String
    val archiveName = URI(url).path.substringAfterLast('/')
    val archive = File(sttCache, "dl/$label/$platform/${sha.take(12)}-$archiveName")
    downloadVerified(url, sha, archive)
    val extractDir = File(sttCache, "tools/$label/$platform/$sha")
    // Written after a complete unpack; the number changes with the unpack method, so a
    // directory from an older method or an interrupted run is unpacked again.
    val unpacked = File(extractDir, ".stt-unpacked-2")
    if (!unpacked.isFile) {
        extractDir.deleteRecursively()
        extractDir.mkdirs()
        if (archiveName.endsWith(".jar") || archiveName.endsWith(".zip")) {
            project.copy { into(extractDir); from(project.zipTree(archive)) }
        } else {
            // The system tar keeps the archive's symlinks (libwhisper.so.1 -> libwhisper.so.1.8.4);
            // Gradle's tarTree copy turns them into empty files, which the loader rejects.
            providers.exec { commandLine("tar", "-xzf", archive.absolutePath, "-C", extractDir.absolutePath) }
                .result.get().assertNormalExitValue()
        }
        unpacked.writeText("")
    }
    return findExecutable(extractDir, exeName)
}

// bytedeco's Linux libav* libraries carry no RUNPATH of their own, so the libraries they
// load (libva, libdrm, also bundled) are found only through LD_LIBRARY_PATH. A generated
// `ffmpeg` script, first on PATH, sets it for ffmpeg alone and execs the real binary.
// (macOS needs none: the dylibs resolve through @rpath = @loader_path.)
fun linuxLibraryPathWrapper(exe: File): File {
    val libDir = exe.parentFile.absolutePath
    val wrapperDir = File(exe.parentFile, "stt-bin")
    wrapperDir.mkdirs()
    val wrapper = File(wrapperDir, exe.name)
    wrapper.writeText(
        "#!/bin/sh\n" +
            "# Generated by ./gradlew prepareRuntime (roadmap item 33): see linuxLibraryPathWrapper in build.gradle.kts.\n" +
            "LD_LIBRARY_PATH='$libDir'\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH} exec '$libDir/${exe.name}' \"\$@\"\n",
    )
    wrapper.setExecutable(true, false)
    return wrapperDir
}

fun prepareModel(key: String) {
    val entry = models[key] as Map<*, *>
    val url = entry["url"] as String
    val sha = entry["sha256"] as String
    val fileName = URI(url).path.substringAfterLast('/')
    val target = File(sttCache, "models/${sha.take(12)}-$fileName")
    downloadVerified(url, sha, target)
}

tasks.register("prepareRuntime") {
    group = "build"
    description = "Download, sha256-verify and unpack the runtime tools and models from versions.json into the Gradle user home"
    doLast {
        for ((label, entry) in listOf("whisper-cli" to whisperCliEntry, "ffmpeg" to ffmpegEntry)) {
            if (entry == null) {
                throw GradleException("versions.json has no $label download for $platform (supported: linux-x64, darwin-x64, darwin-arm64)")
            }
            val exe = prepareTool(label, entry, label)
            val binDir = if (label == "ffmpeg" && platform.startsWith("linux")) linuxLibraryPathWrapper(exe) else exe.parentFile
            runtimeBinDirs.add(binDir.absoluteFile)
        }
        prepareModel("whisper")
        prepareModel("speaker")
    }
}

val backendInstallDist = gradle.includedBuild("backend").task(":installDist")
val backendMain = rootProject.file("backend/build/install/stt-backend/bin/stt-backend")
val jdkMajor = (manifest["jdk"] as String).substringBefore('.')
// Resolved lazily at execution: the current JVM when it is the pinned JDK, else
// foojay downloads it (the resolver is root-only, from settings.gradle.kts).
val runtimeJavaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkMajor)) }
    .map { it.metadata.installationPath.asFile.absolutePath }

// The node-gradle plugin's install location, set in frontend/build.gradle.kts:
// node/pnpm live under $GRADLE_USER_HOME/stt-app/node, shared across clones.
fun nodeInstallDir(): File = File(sttCache, "node")

fun appEnv(): Map<String, String> = mapOf(
    // The managed tools on PATH (ADR 0047), then node/pnpm from the Gradle-managed
    // install, so the Vite server and the backend runner use the pinned versions.
    "PATH" to (runtimeBinDirs.map { it.absolutePath } + nodeInstallDir().let { n ->
        listOf(
            File(n, "pnpm/pnpm-v${manifest["pnpm"]}/bin").absolutePath,
            File(n, "nodejs/node-v${manifest["node"]}-${nodePlatform()}/bin").absolutePath,
        )
    } + System.getenv("PATH")).joinToString(":"),
    "JAVA_HOME" to runtimeJavaHome.get(),
    // The model defaults are the paths the cache *will* hold after prepareRuntime
    // (which both tasks depend on), not a value only set during its execution.
    "STT_WHISPER_MODEL" to (System.getenv("STT_WHISPER_MODEL") ?: modelCachePath("whisper", "ggml-large-v3-turbo-q5_0.bin")),
    "STT_SPEAKER_MODEL" to (System.getenv("STT_SPEAKER_MODEL") ?: modelCachePath("speaker", "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx")),
    "STT_DATA_DIR" to (System.getenv("STT_DATA_DIR") ?: defaultDataDir()),
    "STT_STATIC_DIR" to (System.getenv("STT_STATIC_DIR") ?: rootProject.file("frontend/dist").absolutePath),
)

// The cache paths matched by prepareModel/prepareRuntime: <sha256-prefix>-<file name>.
fun modelCachePath(key: String, fileName: String): String {
    val sha = (models[key] as Map<*, *>)["sha256"] as String
    return File(sttCache, "models/${sha.take(12)}-$fileName").absolutePath
}

// The distribution directory name the node plugin uses for this OS/arch: the OS name
// from the node download (linux-x64, darwin-arm64, darwin-x64), which differs from
// the Gradle platform string only in the OS portion (macosx-* vs darwin-*).
fun nodePlatform(): String = when {
    platform == "linux-x64" -> "linux-x64"
    platform == "darwin-arm64" -> "darwin-arm64"
    else -> "darwin-x64"
}

tasks.register<Exec>("dev") {
    group = "run"
    description = "Dev mode for the Gradle bootstrap (roadmap item 33): the Vite dev server with hot module reload and the backend through item 34's dev-run.sh (rebuild and restart on source change)"
    dependsOn("prepareRuntime", ":frontend:pnpmInstall")
    doFirst {
        // appEnv() plus the runner's hook: item 34's dev-run.sh rebuilds with the
        // repo's wrapper (DEV_RUN_GRADLE), not a bare `gradle`.
        applyAppEnv(this as Exec)
        environment("DEV_RUN_GRADLE", rootProject.file("gradlew").absolutePath)
        workingDir = rootProject.projectDir
        setCommandLine(rootProject.file("dev.sh").absolutePath)
    }
}

tasks.register<Exec>("run") {
    group = "run"
    description = "Build backend + frontend, fetch the runtime tools and models, and start the app on http://localhost:8080 (roadmap item 33)"
    dependsOn("prepareRuntime", backendInstallDist, ":frontend:frontendBuild")
    doFirst {
        applyAppEnv(this as Exec)
        setCommandLine(backendMain.absolutePath)
    }
}

// The backend's environment, shared by `run` and `dev`: the managed tools come first
// on PATH (ADR 0047), and a user-set STT_* wins, otherwise the default comes from
// versions.json/the manifest (like the packaged wrapper's --set-default).
fun applyAppEnv(task: Exec) = appEnv().forEach { (k, v) -> task.environment(k, requireNotNull(v)) }

// ---- e2e: the item-21 Playwright suite without Nix (roadmap item 33, discovered) ----
// Gradle provides what the devenv otherwise sets: the pinned fixtures, the managed
// tools on PATH, the browsers and the backend command. A user-set variable wins.
val e2eDir = rootProject.file("e2e")
val pnpmExe = File(nodeInstallDir(), "pnpm/pnpm-v${manifest["pnpm"]}/bin/pnpm")
val fixtures = manifest["fixtures"] as Map<*, *>

// The cache file for a versions.json asset: <dir>/<sha256-prefix>-<URL file name>.
fun assetCacheFile(dir: String, entry: Map<*, *>): File {
    val sha = entry["sha256"] as String
    val fileName = URI(entry["url"] as String).path.substringAfterLast('/')
    return File(sttCache, "$dir/${sha.take(12)}-$fileName")
}

val jfkWavFile = assetCacheFile("fixtures", fixtures["jfkWav"] as Map<*, *>)
val tinyModelFile = assetCacheFile("models", models["whisperTinyEn"] as Map<*, *>)
// Playwright's own browser download, shared across clones like the other tools.
val playwrightBrowsers = System.getenv("PLAYWRIGHT_BROWSERS_PATH") ?: File(sttCache, "ms-playwright").absolutePath

// The suite's environment: the app's (managed tools and node/pnpm on PATH, JDK 21, the
// speaker model) plus the fixtures, the browsers and the backend command.
fun applyE2eEnv(task: Exec) {
    applyAppEnv(task)
    task.environment("PLAYWRIGHT_BROWSERS_PATH", playwrightBrowsers)
    task.environment("TEST_JFK_WAV", System.getenv("TEST_JFK_WAV") ?: jfkWavFile.absolutePath)
    task.environment("TEST_WHISPER_TINY_EN_MODEL", System.getenv("TEST_WHISPER_TINY_EN_MODEL") ?: tinyModelFile.absolutePath)
    // The installed backend `run` starts, relative to the repository root (the harness's
    // working directory), so a checkout path with spaces cannot break the command line.
    task.environment("E2E_BACKEND_CMD", System.getenv("E2E_BACKEND_CMD") ?: backendMain.relativeTo(rootProject.projectDir).path)
    task.workingDir = e2eDir
}

tasks.register("prepareE2eFixtures") {
    group = "verification"
    description = "Download and sha256-verify the e2e fixtures from versions.json (jfk.wav, the tiny whisper model) into the Gradle user home"
    doLast {
        for ((entry, target) in listOf(fixtures["jfkWav"] to jfkWavFile, models["whisperTinyEn"] to tinyModelFile)) {
            entry as Map<*, *>
            downloadVerified(entry["url"] as String, entry["sha256"] as String, target)
        }
    }
}

tasks.register<Exec>("e2eInstall") {
    group = "verification"
    description = "Install the e2e package's dependencies (frozen lockfile) with the managed pnpm"
    dependsOn("prepareRuntime", ":frontend:pnpmSetup")
    doFirst {
        applyE2eEnv(this as Exec)
        setCommandLine(pnpmExe.absolutePath, "install", "--frozen-lockfile")
    }
}

tasks.register<Exec>("e2eBrowsers") {
    group = "verification"
    description = "Download the Chromium the e2e package's Playwright expects into the Gradle user home"
    dependsOn("e2eInstall")
    // A user-set PLAYWRIGHT_BROWSERS_PATH (the devenv's read-only Nix browser farm) is used as is.
    onlyIf { System.getenv("PLAYWRIGHT_BROWSERS_PATH") == null }
    doFirst {
        applyE2eEnv(this as Exec)
        setCommandLine(pnpmExe.absolutePath, "exec", "playwright", "install", "chromium")
    }
}

tasks.register<Exec>("e2e") {
    group = "verification"
    description = "Run the item-21 e2e suite against the built app with the Gradle-managed tools, fixtures and browsers (Chromium's system libraries: 'playwright install-deps chromium')"
    dependsOn("prepareRuntime", "prepareE2eFixtures", "e2eBrowsers", backendInstallDist, ":frontend:frontendBuild")
    doFirst {
        applyE2eEnv(this as Exec)
        setCommandLine(pnpmExe.absolutePath, "test")
    }
}