package stt.service

import org.slf4j.LoggerFactory
import java.nio.file.Path

/** The operating system whose per-user directory conventions apply (roadmap item 66). */
enum class Platform {
    Linux,
    MacOs,
    Windows,
    ;

    companion object {
        /** The running system, from the JVM's OS name. */
        fun current(): Platform {
            val name = System.getProperty("os.name").lowercase()
            return when {
                name.startsWith("mac") -> MacOs
                name.startsWith("windows") -> Windows
                else -> Linux
            }
        }
    }
}

/**
 * The per-user directories of the service (roadmap item 66): [configDir] holds the durable `config.json`, [runtimeDir]
 * the ephemeral `runtime.json` and the instance lock. Each OS's own per-user locations hold them, under `stt-app`;
 * `STT_CONFIG_DIR` and `STT_RUNTIME_DIR` override them, which is how the specs, the e2e harness and `devenv` isolate a
 * run. The rule is shared with the Rust client through `docs/service-dirs.json`: both are tested against its cases.
 */
data class ServiceDirs(
    val configDir: Path,
    val runtimeDir: Path,
) {
    /** Resolved [dirs], and a [warning] when the runtime directory is a fallback that a logout does not clear. */
    data class Resolution(
        val dirs: ServiceDirs,
        val warning: String?,
    )

    companion object {
        private const val APP_DIR = "stt-app"
        private val logger = LoggerFactory.getLogger(ServiceDirs::class.java)

        /** The directories of this process's user, logging the fallback warning, if any. */
        fun fromEnv(): ServiceDirs {
            val resolution = resolve(System.getenv(), Platform.current())
            resolution.warning?.let(logger::warn)
            return resolution.dirs
        }

        /** The directories [env] names on [platform]; an empty variable counts as unset. */
        fun resolve(
            env: Map<String, String>,
            platform: Platform,
        ): Resolution {
            val set = env.filterValues { it.isNotEmpty() }
            val configDir = set["STT_CONFIG_DIR"]?.let(Path::of) ?: Path.of(defaultConfigBase(set, platform), APP_DIR)
            val override = set["STT_RUNTIME_DIR"]
            if (override != null) return Resolution(ServiceDirs(configDir, Path.of(override)), null)
            val runtime = defaultRuntimeBase(set, platform)
            val runtimeDir = Path.of(runtime.base, APP_DIR)
            val warning =
                runtime.missing?.let {
                    "$it is not set: keeping the service's runtime files in $runtimeDir, which a logout does not clear"
                }
            return Resolution(ServiceDirs(configDir, runtimeDir), warning)
        }

        /** Where the OS keeps a user's app configuration. */
        private fun defaultConfigBase(
            env: Map<String, String>,
            platform: Platform,
        ): String =
            when (platform) {
                Platform.Linux -> env["XDG_CONFIG_HOME"] ?: "${home(env)}/.config"
                Platform.MacOs -> "${home(env)}/Library/Application Support"
                Platform.Windows -> env["APPDATA"] ?: "${home(env)}/AppData/Roaming"
            }

        /** A runtime base directory, and the variable whose absence made it a fallback. */
        private data class RuntimeBase(
            val base: String,
            val missing: String?,
        )

        /** Where the OS keeps a user's ephemeral files — the ones a logout or reboot clears. */
        private fun defaultRuntimeBase(
            env: Map<String, String>,
            platform: Platform,
        ): RuntimeBase =
            when (platform) {
                Platform.Linux -> {
                    env["XDG_RUNTIME_DIR"]?.let { RuntimeBase(it, null) }
                        ?: RuntimeBase("${home(env)}/.cache", "XDG_RUNTIME_DIR")
                }

                Platform.MacOs -> {
                    env["TMPDIR"]?.let { RuntimeBase(it, null) }
                        ?: RuntimeBase("${home(env)}/Library/Caches", "TMPDIR")
                }

                Platform.Windows -> {
                    RuntimeBase("${env["LOCALAPPDATA"] ?: "${home(env)}/AppData/Local"}/Temp", null)
                }
            }

        /** The user's home directory; without it no per-user location can be named. */
        private fun home(env: Map<String, String>): String =
            env["HOME"] ?: env["USERPROFILE"]
                ?: error("HOME (or USERPROFILE on Windows) must be set to locate the service's directories")
    }
}
