// Root Gradle build for human developers without Nix (roadmap item 33). The version
// manifest (versions.json) is the single source for every pinned version and
// download (`checkVersions` fails when it drifts from the wrapper or the locked
// nixpkgs - cycle 1). `run` builds the backend (composite build) and the frontend
// (node-plugin subproject), fetches and sha256-verifies the runtime tools and models
// into the Gradle user home, prepends the managed tools' directories to PATH
// (ADR 0047: no STT_WHISPER_CLI/STT_FFMPEG) and starts the backend with the STT_*
// environment resolved from versions.json.
import groovy.json.JsonSlurper
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

plugins {
    // java-base only: gives the root build the javaToolchains service (the JDK-21
    // runtime resolution) without java compilation tasks.
    id("java-base")
}

val manifest = JsonSlurper().parse(rootProject.file("versions.json")) as Map<*, *>

tasks.register("checkVersions") {
    group = "verification"
    description = "Fail when the committed versions.json drifts from the Gradle wrapper or the locked nixpkgs (roadmap item 33)"
    doLast {
        // 1. The wrapper's Gradle version must match the manifest (both are committed copies).
        val wrapperProperties = Properties()
        rootProject.file("gradle/wrapper/gradle-wrapper.properties").inputStream().use(wrapperProperties::load)
        val distributionUrl = wrapperProperties.getProperty("distributionUrl")
        val wrapperVersion =
            Regex("gradle-([^/]+)-bin\\.zip").find(distributionUrl)?.groupValues?.get(1)
                ?: throw GradleException("Cannot parse the Gradle version from distributionUrl: $distributionUrl")
        val manifestGradle = manifest["gradle"] as String
        if (wrapperVersion != manifestGradle) {
            throw GradleException(
                "versions.json pins Gradle $manifestGradle but the wrapper " +
                    "(gradle/wrapper/gradle-wrapper.properties) is $wrapperVersion. " +
                    "Run 'gradle wrapper --gradle-version $manifestGradle' at the repository root and commit the result.",
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
                    "On a machine with Nix, run 'nix run .#update-versions' at the repository root and commit the result.",
            )
        }

        // 3. Windows (roadmap item 49): the platform is supported and every tool the bootstrap
        //    downloads, plus the desktop's JDK, has a pinned windows-x64 artifact. The download,
        //    sha256 check and .exe resolution run on Windows; this keeps the manifest honest on
        //    any host, since the bootstrap reads the committed versions.json without Nix.
        fun verifyWindowsPins() {
            val windowsPlatform = "windows-x64"
            if (windowsPlatform !in supportedPlatforms) {
                throw GradleException(
                    "supportedPlatforms has no $windowsPlatform, so versions.json has no Windows tool pins " +
                        "(supported: ${supportedPlatforms.joinToString()}).",
                )
            }
            val downloads = manifest["downloads"] as Map<*, *>
            val windowsAssets =
                mapOf(
                    "whisper-cli" to "whisper-1.8.4-windows-x64.tar.gz",
                    "ffmpeg" to "windows-x86_64-gpl.jar",
                    "temurin-jdk" to "windows_hotspot",
                )
            for ((tool, asset) in windowsAssets) {
                val table = downloads[tool] as Map<*, *>?
                val entry =
                    (table?.get(windowsPlatform) as Map<*, *>?)
                        ?: throw GradleException("versions.json has no $tool download for $windowsPlatform")
                val url = entry["url"] as String?
                if (url == null || !url.contains(asset)) {
                    throw GradleException("versions.json's $tool pin for $windowsPlatform must point at $asset, got $url")
                }
                val sha = entry["sha256"] as String?
                if (sha == null || !Regex("[0-9a-f]{64}").matches(sha)) {
                    throw GradleException("versions.json's $tool pin for $windowsPlatform needs a 64-hex sha256, got $sha")
                }
            }
        }

        verifyWindowsPins()

        logger.lifecycle(
            "checkVersions: versions.json is in sync (Gradle $manifestGradle, nixpkgs ${lockRev.take(12)}, " +
                "Windows pins present)",
        )
    }
}

// ---- platform: Linux x64 and Windows x64 supported, macOS best-effort (no testing) ----
// Null on any other OS/architecture. Tasks that need no downloaded tool (checkVersions)
// still run there; a build that would download or start one fails before any task runs.
val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
val osArch = System.getProperty("os.arch").lowercase(Locale.ROOT)
val supportedPlatforms = listOf("linux-x64", "darwin-x64", "darwin-arm64", "windows-x64")
val platform: String? =
    when {
        osName.contains("mac") && osArch == "aarch64" -> "darwin-arm64"
        osName.contains("mac") -> "darwin-x64"
        osName.contains("windows") && (osArch == "amd64" || osArch == "x86_64") -> "windows-x64"
        osName.contains("linux") && (osArch == "amd64" || osArch == "x86_64") -> "linux-x64"
        else -> null
    }

fun requirePlatform(): String =
    platform ?: throw GradleException(
        "The Gradle bootstrap does not support this platform: $osName/$osArch. " +
            "Supported: ${supportedPlatforms.joinToString()} (Linux x64 and Windows x64 tested, macOS best-effort).",
    )

/** A managed tool's file name on this platform: Windows executables carry `.exe` (roadmap item 49). */
fun platformExecutable(name: String): String = if (requirePlatform().startsWith("windows")) "$name.exe" else name

// Every task that downloads or runs a managed tool: prepareRuntime (whisper-cli, ffmpeg,
// models; run, dev and e2e depend on it) and e2eInstall (node/pnpm; e2eBrowsers,
// e2eSystemDeps and e2e depend on it). Checked once the task graph is known, so the
// build fails before its first task, not halfway through the frontend's node setup.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == rootProject && it.name in setOf("prepareRuntime", "e2eInstall") }) {
        requirePlatform()
    }
}

// Downloads are cached under the Gradle user home, so a second clone reuses them.
val sttCache =
    File(
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
fun downloadVerified(
    url: String,
    expectedSha256: String,
    target: File,
) {
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

fun findExecutable(
    root: File,
    name: String,
): File {
    val exe =
        root.walkTopDown().filter { it.isFile && it.name == name }.minByOrNull { it.path.length }
            ?: throw GradleException("$name not found under $root")
    try {
        exe.setExecutable(true, false)
    } catch (_: Exception) {
        // non-POSIX or read-only fs: the run fails loudly later if it matters
    }
    return exe
}

fun defaultDataDir(): String {
    if (platform == "windows-x64") {
        // %APPDATA%\stt-app (roadmap item 49), the directory the desktop app also uses.
        val appData = System.getenv("APPDATA")
        val base =
            if (!appData.isNullOrEmpty()) {
                appData
            } else {
                File(System.getenv("USERPROFILE") ?: "", "AppData\\Roaming").absolutePath
            }
        return "$base\\stt-app"
    }
    if (platform?.startsWith("darwin") == true) {
        return (System.getenv("HOME") ?: "") + "/Library/Application Support/stt-app"
    }
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
fun prepareTool(
    label: String,
    entry: Map<*, *>,
    exeName: String,
): File {
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
            project.copy {
                into(extractDir)
                from(project.zipTree(archive))
            }
        } else {
            // The system tar keeps the archive's symlinks (libwhisper.so.1 -> libwhisper.so.1.8.4);
            // Gradle's tarTree copy turns them into empty files, which the loader rejects.
            providers
                .exec { commandLine("tar", "-xzf", archive.absolutePath, "-C", extractDir.absolutePath) }
                .result
                .get()
                .assertNormalExitValue()
        }
        unpacked.writeText("")
    }
    return findExecutable(extractDir, exeName)
}

// bytedeco's Linux libav* libraries carry no RUNPATH of their own, so the libraries they load
// (libva, libdrm, also bundled) are found only through LD_LIBRARY_PATH. The generated wrappers,
// first on PATH, set it for one tool and exec the real binary. The extract directory is never on
// PATH itself, so every executable an archive ships needs its own wrapper.
// (macOS needs no wrapper: the dylibs resolve through @rpath = @loader_path.)
fun linuxLibraryPathWrapper(tools: List<File>): File {
    val wrapperDir = File(tools.first().parentFile, "stt-bin")
    wrapperDir.mkdirs()
    for (tool in tools) {
        val libDir = tool.parentFile.absolutePath
        val wrapper = File(wrapperDir, tool.name)
        wrapper.writeText(
            "#!/bin/sh\n" +
                "# Generated by ./gradlew prepareRuntime (roadmap item 33): see linuxLibraryPathWrapper in build.gradle.kts.\n" +
                "LD_LIBRARY_PATH='$libDir'\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH} exec '$libDir/${tool.name}' \"\$@\"\n",
        )
        wrapper.setExecutable(true, false)
    }
    return wrapperDir
}

// The cache file for a versions.json asset: <dir>/<sha256-prefix>-<URL file name>.
fun assetCacheFile(
    dir: String,
    entry: Map<*, *>,
): File {
    val sha = entry["sha256"] as String
    val fileName = URI(entry["url"] as String).path.substringAfterLast('/')
    return File(sttCache, "$dir/${sha.take(12)}-$fileName")
}

fun modelCacheFile(key: String): File = assetCacheFile("models", models[key] as Map<*, *>)

fun prepareModel(key: String) {
    val entry = models[key] as Map<*, *>
    downloadVerified(entry["url"] as String, entry["sha256"] as String, modelCacheFile(key))
}

tasks.register("prepareRuntime") {
    group = "build"
    description = "Download, sha256-verify and unpack the runtime tools and models from versions.json into the Gradle user home"
    doLast {
        for ((label, entry) in listOf("whisper-cli" to whisperCliEntry, "ffmpeg" to ffmpegEntry)) {
            if (entry == null) {
                throw GradleException(
                    "versions.json has no $label download for $platform " +
                        "(supported: ${supportedPlatforms.joinToString()})",
                )
            }
            val exe = prepareTool(label, entry, platformExecutable(label))
            // An archive can ship more than one executable: the bytedeco ffmpeg jar carries ffmpeg
            // and ffprobe. Nothing runs ffprobe (the shipped ffmpeg does not have it), so only
            // ffmpeg is resolved. Gradle's zip copy drops the executable bit, so findExecutable
            // restores it.
            val executables = listOf(findExecutable(exe.parentFile, exe.name))
            val binDir =
                if (label == "ffmpeg" && requirePlatform().startsWith("linux")) {
                    linuxLibraryPathWrapper(executables)
                } else {
                    exe.parentFile
                }
            runtimeBinDirs.add(binDir.absoluteFile)
        }
        prepareModel("whisper")
        prepareModel("speaker")
    }
}

val backendInstallDist = gradle.includedBuild("backend").task(":installDist")
val backendMain =
    rootProject.file(
        if (platform == "windows-x64") {
            "backend/build/install/stt-backend/bin/stt-backend.bat"
        } else {
            "backend/build/install/stt-backend/bin/stt-backend"
        },
    )
val jdkMajor = (manifest["jdk"] as String).substringBefore('.')
// Resolved lazily at execution: the current JVM when it is the pinned JDK, else
// foojay downloads it (the resolver is root-only, from settings.gradle.kts).
val runtimeJavaHome =
    javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkMajor)) }
        .map { it.metadata.installationPath.asFile.absolutePath }

// The node-gradle plugin's install location, set in frontend/build.gradle.kts:
// node/pnpm live under $GRADLE_USER_HOME/stt-app/node, shared across clones.
fun nodeInstallDir(): File = File(sttCache, "node")

fun appEnv(): Map<String, String> =
    mapOf(
        // The managed tools on PATH (ADR 0047), then node/pnpm from the Gradle-managed
        // install, so the Vite server and the backend runner use the pinned versions.
        "PATH" to
            (
                runtimeBinDirs.map { it.absolutePath } +
                    nodeInstallDir().let { n ->
                        listOf(
                            File(n, "pnpm/pnpm-v${manifest["pnpm"]}/bin").absolutePath,
                            File(n, "nodejs/node-v${manifest["node"]}-${nodePlatform()}")
                                .let { if (platform == "windows-x64") it.absolutePath else File(it, "bin").absolutePath },
                        )
                    } + System.getenv("PATH")
            ).joinToString(File.pathSeparator),
        "JAVA_HOME" to runtimeJavaHome.get(),
        // Gradle 9's HTML test report writes one directory per test name, and names like "0°, 20°"
        // or "O(n³)" fail to encode under a POSIX locale: devenv.nix and flake.nix set the same for
        // the same reason, and this no-Nix path needs it too. LC_CTYPE sets the charset only.
        "LC_CTYPE" to (System.getenv("LC_CTYPE") ?: if (platform?.startsWith("darwin") == true) "UTF-8" else "C.UTF-8"),
        // The model defaults are the paths the cache *will* hold after prepareRuntime
        // (which both tasks depend on), not a value only set during its execution.
        "STT_WHISPER_MODEL" to (System.getenv("STT_WHISPER_MODEL") ?: modelCacheFile("whisper").absolutePath),
        "STT_SPEAKER_MODEL" to (System.getenv("STT_SPEAKER_MODEL") ?: modelCacheFile("speaker").absolutePath),
        "STT_DATA_DIR" to (System.getenv("STT_DATA_DIR") ?: defaultDataDir()),
        "STT_STATIC_DIR" to (System.getenv("STT_STATIC_DIR") ?: rootProject.file("frontend/dist").absolutePath),
    )

// The distribution directory name the node plugin uses for this OS/arch: the OS name
// from the node download (linux-x64, darwin-arm64, darwin-x64), which differs from
// the Gradle platform string only in the OS portion (macosx-* vs darwin-*).
fun nodePlatform(): String =
    when {
        platform == "linux-x64" -> "linux-x64"
        platform == "windows-x64" -> "win-x64"
        platform == "darwin-arm64" -> "darwin-arm64"
        else -> "darwin-x64"
    }

tasks.register<Exec>("dev") {
    group = "run"
    description =
        "Dev mode for the Gradle bootstrap (roadmap item 33): the Vite dev server with hot module reload and the backend through item 34's dev-run.sh (rebuild and restart on source change)"
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
    description =
        "Build backend + frontend, fetch the runtime tools and models, and start the app on http://localhost:8080 (roadmap item 33)"
    dependsOn("prepareRuntime", backendInstallDist, ":frontend:frontendBuild")
    doFirst {
        applyAppEnv(this as Exec)
        // A .bat is not a Win32 executable; cmd runs it, as the installDist launcher expects.
        setCommandLine(
            if (platform == "windows-x64") {
                listOf("cmd", "/c", backendMain.absolutePath)
            } else {
                listOf(backendMain.absolutePath)
            },
        )
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
val pnpmExe =
    File(
        nodeInstallDir(),
        "pnpm/pnpm-v${manifest["pnpm"]}/bin/" + if (platform == "windows-x64") "pnpm.cmd" else "pnpm",
    )
val fixtures = manifest["fixtures"] as Map<*, *>

val jfkWavFile = assetCacheFile("fixtures", fixtures["jfkWav"] as Map<*, *>)
val tinyModelFile = modelCacheFile("whisperTinyEn")
// Playwright's own browser download, shared across clones like the other tools.
val playwrightBrowsers = System.getenv("PLAYWRIGHT_BROWSERS_PATH") ?: File(sttCache, "ms-playwright").absolutePath

// The pinned test fixtures from versions.json (item 33); the devenv exports the same two.
// A user-set value wins, so a branch-only fixture can be passed explicitly.
fun applyFixtureEnv(task: Exec) {
    task.environment("TEST_JFK_WAV", System.getenv("TEST_JFK_WAV") ?: jfkWavFile.absolutePath)
    task.environment("TEST_WHISPER_TINY_EN_MODEL", System.getenv("TEST_WHISPER_TINY_EN_MODEL") ?: tinyModelFile.absolutePath)
}

// The suite's environment: the app's (managed tools and node/pnpm on PATH, JDK 21, the
// speaker model) plus the fixtures, the browsers and the backend command.
fun applyE2eEnv(task: Exec) {
    applyAppEnv(task)
    task.environment("PLAYWRIGHT_BROWSERS_PATH", playwrightBrowsers)
    applyFixtureEnv(task)
    // The installed backend `run` starts, relative to the repository root (the harness's
    // working directory), so a checkout path with spaces cannot break the command line.
    task.environment("E2E_BACKEND_CMD", System.getenv("E2E_BACKEND_CMD") ?: backendMain.relativeTo(rootProject.projectDir).path)
    task.workingDir = e2eDir
}

tasks.register("prepareE2eFixtures") {
    group = "verification"
    description =
        "Download and sha256-verify the e2e fixtures from versions.json (jfk.wav, the tiny whisper model) into the Gradle user home"
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
    dependsOn(":frontend:pnpmSetup")
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

// Chromium's Linux system libraries come from apt and need root, and a Gradle daemon has
// no terminal for a sudo prompt. So as root (containers, CI) this installs them; as anyone
// else it only checks (Playwright's --dry-run simulates the apt install, no root needed)
// and fails with the sudo line for the missing packages. macOS needs none.
val runningAsRoot by lazy {
    providers
        .exec { commandLine("id", "-u") }
        .standardOutput.asText
        .get()
        .trim() == "0"
}

tasks.register<Exec>("e2eSystemDeps") {
    group = "verification"
    description =
        "Linux: install Chromium's system libraries for the e2e suite when run as root; otherwise check them and print the sudo apt-get line for the missing ones"
    dependsOn("e2eInstall")
    onlyIf { platform?.startsWith("linux") == true }
    val dryRunOutput = ByteArrayOutputStream()
    doFirst {
        applyE2eEnv(this as Exec)
        if (runningAsRoot) {
            // Gradle gives the process no terminal, so apt's debconf must not ask anything.
            environment("DEBIAN_FRONTEND", System.getenv("DEBIAN_FRONTEND") ?: "noninteractive")
            setCommandLine(pnpmExe.absolutePath, "exec", "playwright", "install-deps", "chromium")
        } else {
            setCommandLine(pnpmExe.absolutePath, "exec", "playwright", "install-deps", "--dry-run", "chromium")
            standardOutput = dryRunOutput
            isIgnoreExitValue = true
        }
    }
    doLast {
        if (runningAsRoot) return@doLast
        val report = dryRunOutput.toString()
        // Playwright prints "Missing system dependencies (N):" and one indented package per line.
        val missing =
            report
                .lines()
                .dropWhile { !it.startsWith("Missing system dependencies") }
                .drop(1)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        when {
            missing.isNotEmpty() -> {
                throw GradleException(
                    "Chromium's system libraries are missing (${missing.size} packages). Install them, then run ./gradlew e2e:\n\n" +
                        "    sudo apt-get update && sudo apt-get install -y --no-install-recommends ${missing.joinToString(" ")}\n",
                )
            }

            executionResult.get().exitValue == 0 -> {
                println(report.trim())
            }

            else -> {
                println(report.trim())
                throw GradleException(
                    "playwright install-deps --dry-run failed with exit code ${executionResult.get().exitValue}; its output is above.",
                )
            }
        }
    }
}

tasks.register<Exec>("e2e") {
    group = "verification"
    description =
        "Run the item-21 e2e suite against the built app with the Gradle-managed tools, fixtures and browsers (Chromium's system libraries: ./gradlew e2eSystemDeps)"
    dependsOn("prepareRuntime", "prepareE2eFixtures", "e2eBrowsers", backendInstallDist, ":frontend:frontendBuild")
    doFirst {
        applyE2eEnv(this as Exec)
        setCommandLine(pnpmExe.absolutePath, "test")
    }
}

// ---- integrationTest: the Integration-tagged backend tests without Nix (roadmap item 51) ----
// The backend's own `integrationTest` task (ADR 0021) reads the managed tools and the TEST_*
// fixtures from the environment, which the devenv exports. This wraps it so a clean checkout can
// run `./gradlew integrationTest`, exactly like `e2e`. The nested wrapper invocation is the same
// pattern `dev` uses (DEV_RUN_GRADLE) and keeps the backend a standalone build (ADR 0014).
// `-PtestFilter=<pattern>` narrows the run to matching test classes, the same Gradle `--tests`
// filter the backend task takes (roadmap item 49: one spec on a platform leg, the full suite elsewhere).
tasks.register<Exec>("integrationTest") {
    group = "verification"
    description =
        "Run the backend's Integration-tagged tests with the Gradle-managed tools, models and fixtures, without Nix (roadmap item 51)"
    // runtimeSystemDeps checks the libraries the prebuilt tools link against first, so a plain host
    // gets the actionable `sudo apt-get install …` line instead of a loader error inside a test.
    dependsOn("runtimeSystemDeps", "prepareRuntime", "prepareE2eFixtures")
    doFirst {
        applyAppEnv(this as Exec)
        applyFixtureEnv(this as Exec)
        workingDir = rootProject.projectDir
        // Windows has no POSIX shell to run `gradlew`: it runs `gradlew.bat`, and a .bat needs cmd
        // (roadmap item 49). The backend launcher follows the same rule in the `run` task.
        val wrapper = rootProject.file(if (platform == "windows-x64") "gradlew.bat" else "gradlew")
        setCommandLine(
            if (platform == "windows-x64") {
                listOf("cmd", "/c", wrapper.absolutePath, "-p", "backend", "integrationTest")
            } else {
                listOf(wrapper.absolutePath, "-p", "backend", "integrationTest")
            },
        )
        providers.gradleProperty("testFilter").orNull?.let { args("--tests", it) }
    }
}

// ---- runtimeSystemDeps: the prebuilt runtime tools' Linux system libraries (roadmap item 51) ----
// whisper-cli and ffmpeg link against system libraries that Nix, devenv and the Docker image bring
// themselves, but a plain Linux host must install. This list is their single owner: README § "Gradle
// without Nix" points here and CI runs this task, so the two cannot drift. The shape is
// e2eSystemDeps's for Chromium's libraries — as root it installs them, as anyone else it only
// checks and, when something is missing, fails with the exact `sudo apt-get install …` line, since
// a Gradle-spawned process has no terminal for a password prompt.
val runtimeSystemLibraries =
    listOf(
        "libgomp1", // whisper-cli
        "libvulkan1", // whisper-cli: the Vulkan loader, needed even without a GPU
        "libstdc++6", // whisper-cli
        "libasound2t64", // ffmpeg (libasound2 before Ubuntu 24.04 / Debian 13)
        "libpulse0",
        "libxcb1",
        "libxcb-shm0",
        "libudev1",
    )

/** Whether [name] is missing, or null when this machine has no dpkg to ask (not Debian/Ubuntu). */
fun debianPackageMissing(name: String): Boolean? =
    try {
        providers
            .exec {
                commandLine("dpkg-query", "-s", name)
                isIgnoreExitValue = true
            }.result
            .get()
            .exitValue != 0
    } catch (_: Exception) {
        null
    }

tasks.register("runtimeSystemDeps") {
    group = "build"
    description =
        "Linux: as root, install the system libraries the prebuilt whisper-cli and ffmpeg link against; otherwise check them and print the apt-get line for the missing ones"
    onlyIf { platform?.startsWith("linux") == true }
    doLast {
        if (runningAsRoot) {
            val commands =
                listOf(
                    listOf("apt-get", "update"),
                    listOf("apt-get", "install", "-y", "--no-install-recommends") + runtimeSystemLibraries,
                )
            for (command in commands) {
                providers
                    .exec {
                        commandLine(command)
                        environment("DEBIAN_FRONTEND", System.getenv("DEBIAN_FRONTEND") ?: "noninteractive")
                    }.result
                    .get()
                    .assertNormalExitValue()
            }
            logger.lifecycle("runtimeSystemDeps: installed ${runtimeSystemLibraries.joinToString(" ")}")
            return@doLast
        }
        val statuses = runtimeSystemLibraries.map { it to debianPackageMissing(it) }
        val missing = statuses.filter { it.second == true }.map { it.first }
        when {
            statuses.all { it.second == null } -> {
                logger.warn(
                    "runtimeSystemDeps cannot check the system libraries (no dpkg on this distribution). " +
                        "whisper-cli and ffmpeg are linked against: ${runtimeSystemLibraries.joinToString(" ")}",
                )
            }

            missing.isEmpty() -> {
                logger.lifecycle("runtimeSystemDeps: all system libraries are installed.")
            }

            else -> {
                throw GradleException(
                    "The prebuilt whisper-cli and ffmpeg need ${missing.size} system libraries this machine has not installed. " +
                        "Install them, then run the command again:\n\n" +
                        "    sudo apt-get update && sudo apt-get install -y --no-install-recommends ${missing.joinToString(" ")}\n",
                )
            }
        }
    }
}
