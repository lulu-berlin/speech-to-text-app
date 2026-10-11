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

// Downloads url into target after verifying its sha256 against the manifest entry.
// A verified file is never downloaded again (item 33: second clones reuse the cache).
fun downloadVerified(url: String, expectedSha256: String, target: File) {
    if (target.isFile) return
    target.parentFile.mkdirs()
    val tmp = File.createTempFile("stt-download", ".part", target.parentFile)
    try {
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
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

// prepareRuntime writes these; the run task reads them at execution time.
val runtimeBinDirs = mutableListOf<File>()
var runtimeWhisperModel: File? = null
var runtimeSpeakerModel: File? = null

// Downloads, verifies and unpacks one tool; the dir holding the executable is
// prepended to PATH for the backend (ADR 0047). .zip and .tar.gz/.tar.xz are
// supported (whisper-cli archives, the BtbN .tar.xz and the macOS .zip ffmpeg).
fun prepareTool(label: String, entry: Map<*, *>, exeName: String): File {
    val url = entry["url"] as String
    val sha = entry["sha256"] as String
    val archiveName = URI(url).path.substringAfterLast('/')
    val archive = File(sttCache, "dl/$label/$platform/${sha.take(12)}-$archiveName")
    if (!archive.isFile) downloadVerified(url, sha, archive)
    val extractDir = File(sttCache, "tools/$label/$platform/$sha")
    if (!extractDir.isDirectory || extractDir.listFiles().isNullOrEmpty()) {
        extractDir.mkdirs()
        if (archiveName.endsWith(".tar.xz")) {
            // Gradle's tarTree does not decompress XZ (silently extracts nothing).
            project.exec { commandLine("tar", "-xJf", archive.absolutePath, "-C", extractDir.absolutePath) }
        } else if (archiveName.endsWith(".zip")) {
            project.copy { into(extractDir); from(project.zipTree(archive)) }
        } else {
            project.copy { into(extractDir); from(project.tarTree(archive)) }
        }
    }
    val exe = findExecutable(extractDir, exeName)
    runtimeBinDirs.add(exe.parentFile.absoluteFile)
    return exe.parentFile.absoluteFile
}

fun prepareModel(key: String): File {
    val entry = models[key] as Map<*, *>
    val url = entry["url"] as String
    val sha = entry["sha256"] as String
    val fileName = URI(url).path.substringAfterLast('/')
    val target = File(sttCache, "models/${sha.take(12)}-$fileName")
    if (!target.isFile) downloadVerified(url, sha, target)
    return target
}

tasks.register("prepareRuntime") {
    group = "build"
    description = "Download, sha256-verify and unpack the runtime tools and models from versions.json into the Gradle user home"
    doLast {
        if (whisperCliEntry != null) {
            prepareTool("whisper-cli", whisperCliEntry, "whisper-cli")
        } else {
            throw GradleException("versions.json has no whisper-cli download for $platform (supported: linux-x64, darwin-x64, darwin-arm64)")
        }
        if (ffmpegEntry != null) {
            prepareTool("ffmpeg", ffmpegEntry, "ffmpeg")
        } else {
            logger.warn("versions.json has no ffmpeg download for $platform - the backend will resolve ffmpeg from PATH (macOS best-effort, see README)")
        }
        runtimeWhisperModel = prepareModel("whisper")
        runtimeSpeakerModel = prepareModel("speaker")
    }
}

val backendInstallDist = gradle.includedBuild("backend").task(":installDist")
val backendMain = rootProject.file("backend/build/install/stt-backend/bin/stt-backend")
val jdkMajor = (manifest["jdk"] as String).substringBefore('.')
// Resolved lazily at execution: the current JVM when it is the pinned JDK, else
// foojay downloads it (the resolver is root-only, from settings.gradle.kts).
val runtimeJavaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkMajor)) }
    .map { it.metadata.installationPath.asFile.absolutePath }

tasks.register<Exec>("run") {
    group = "run"
    description = "Build backend + frontend, fetch the runtime tools and models, and start the app on http://localhost:8080 (roadmap item 33)"
    dependsOn("prepareRuntime", backendInstallDist, ":frontend:frontendBuild")
    doFirst {
        // The managed tools come first on PATH (ADR 0047); a user-set STT_* wins,
        // otherwise the default comes from versions.json/the manifest (like the
        // packaged wrapper's --set-default).
        environment("PATH", (runtimeBinDirs.map { it.absolutePath } + (System.getenv("PATH") ?: "")).joinToString(":"))
        environment("STT_WHISPER_MODEL", System.getenv("STT_WHISPER_MODEL") ?: runtimeWhisperModel!!.absolutePath)
        environment("STT_SPEAKER_MODEL", System.getenv("STT_SPEAKER_MODEL") ?: runtimeSpeakerModel!!.absolutePath)
        environment("STT_STATIC_DIR", System.getenv("STT_STATIC_DIR") ?: rootProject.file("frontend/dist").absolutePath)
        environment("STT_DATA_DIR", System.getenv("STT_DATA_DIR") ?: defaultDataDir())
        setCommandLine(backendMain.absolutePath)
    }
    environment("JAVA_HOME", runtimeJavaHome)
}