package stt.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The durable bootstrap state in `config.json` (roadmap item 66): the port the service serves and the per-install secret. */
@Serializable
data class ServiceConfig(
    val port: Int,
    val secret: String,
)

/** The ephemeral `runtime.json` (roadmap item 66): which process is the running instance and where it answers. */
@Serializable
data class ServiceRuntime(
    val pid: Long,
    val endpoint: String,
)

/**
 * The per-user service instance (roadmap item 66) while this process holds it: the OS lock on `service.lock` in the
 * runtime directory makes it the only one for the user, and [config] is its durable bootstrap state. Closing it
 * releases the lock.
 */
class ServiceInstance private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val dirs: ServiceDirs,
    config: ServiceConfig,
) : AutoCloseable {
    var config: ServiceConfig = config
        private set

    /**
     * Advertises this process as the running instance on [port] in `runtime.json`. A port other than the durable one
     * (the durable port was taken) becomes the durable port in `config.json`.
     */
    suspend fun advertise(
        pid: Long,
        port: Int,
    ) = withContext(Dispatchers.IO) {
        if (port != config.port) {
            config = config.copy(port = port)
            writeConfig(dirs, config)
        }
        replaceAtomically(
            dirs.runtimeDir.resolve(RUNTIME_FILE),
            Json.encodeToString(ServiceRuntime(pid, "http://127.0.0.1:$port")),
        )
    }

    /**
     * Withdraws the advertisement: a stopping service removes `runtime.json`, so no file claims that it still answers.
     * A crash cannot do this; clients then find that the endpoint does not answer as the service (roadmap item 66, D3).
     */
    suspend fun withdraw() =
        withContext(Dispatchers.IO) {
            Files.deleteIfExists(dirs.runtimeDir.resolve(RUNTIME_FILE))
        }

    /**
     * The port to serve on: the durable port when no other program holds it, else [ANY_FREE_PORT] (the OS picks one,
     * which [advertise] then makes durable).
     */
    suspend fun portToServe(host: String): Int =
        withContext(Dispatchers.IO) {
            val free = !isServing(host, config.port)
            if (free) {
                config.port
            } else {
                logger.info("Port {} is taken by another program; serving on a free port", config.port)
                ANY_FREE_PORT
            }
        }

    /**
     * Whether another program listens on [host]:`[port]`.
     *
     * Asking by connecting, not by binding: a bind is not decisive — Java sets SO_REUSEADDR by default and Windows
     * (macOS in practice too) then lets a second program bind a port another one holds — and a bind without
     * SO_REUSEADDR is wrong the other way, because it fails while this service's own finished connections still sit in
     * TIME_WAIT after a restart. A connection that is accepted means someone holds the port; a refused one means
     * nothing answers there, and the bind itself still catches the rest.
     */
    private suspend fun isServing(
        host: String,
        port: Int,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
                true
            } catch (e: IOException) {
                false
            }
        }

    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        private const val LOCK_FILE = "service.lock"

        /** How long the connection probe waits for an answer before calling the port free. */
        private const val PROBE_TIMEOUT_MS = 250
        private const val CONFIG_FILE = "config.json"
        private const val RUNTIME_FILE = "runtime.json"
        private const val SECRET_BYTES = 32
        private val logger = LoggerFactory.getLogger(ServiceInstance::class.java)

        /** The port that asks the OS for any free one. */
        const val ANY_FREE_PORT = 0

        /**
         * Takes the user's instance: null when another process holds it. The instance keeps the durable `config.json`
         * when there is a readable one; the first start, or one that finds the file unreadable, writes it with
         * [defaultPort] and a fresh secret.
         */
        suspend fun claim(
            dirs: ServiceDirs,
            defaultPort: Int,
        ): ServiceInstance? =
            withContext(Dispatchers.IO) {
                createPrivateDirectory(dirs.runtimeDir)
                createPrivateDirectory(dirs.configDir)
                val channel = FileChannel.open(dirs.runtimeDir.resolve(LOCK_FILE), CREATE, WRITE)
                val lock = channel.tryLock()
                if (lock == null) {
                    channel.close()
                    return@withContext null
                }
                val config =
                    readConfig(dirs.configDir.resolve(CONFIG_FILE))
                        ?: ServiceConfig(defaultPort, newSecret()).also { writeConfig(dirs, it) }
                ServiceInstance(channel, lock, dirs, config)
            }

        /**
         * The durable config in [file], or null when there is none or it cannot be read - cut short by a crash while it
         * was written, or edited by hand into something else (roadmap item 66, D4). The caller then replaces it.
         */
        private fun readConfig(file: Path): ServiceConfig? {
            if (!file.exists()) return null
            return try {
                val config = Json.decodeFromString<ServiceConfig>(file.readText())
                if (config.port !in 1..65535) {
                    logger.warn(
                        "{} names port {}, outside 1..65535; replacing it with the default port and a new secret",
                        file,
                        config.port,
                    )
                    return null
                }
                config
            } catch (e: SerializationException) {
                logger.warn("{} cannot be read; replacing it with the default port and a new secret", file, e)
                null
            } catch (e: IOException) {
                logger.warn("{} cannot be read; replacing it with the default port and a new secret", file, e)
                null
            }
        }

        /**
         * Writes [config] as the durable `config.json` in [dirs]' config directory, readable by its owner only: it holds
         * the per-install secret (roadmap item 66). Windows keeps its files under the user's own profile and has no POSIX
         * permissions.
         */
        internal fun writeConfig(
            dirs: ServiceDirs,
            config: ServiceConfig,
        ) {
            replaceAtomically(dirs.configDir.resolve(CONFIG_FILE), Json.encodeToString(config))
        }

        /**
         * Writes [text] as [file], whole or not at all (roadmap item 66, D12): a fresh file beside it, owner-only, moved
         * into place in one step. A crash before the move leaves the old file - and, for `config.json`, its secret - as
         * it was, which is why [readConfig] never has to cope with a file the service itself truncated.
         */
        private fun replaceAtomically(
            file: Path,
            text: String,
        ) {
            val temp = Files.createTempFile(file.parent, "${file.fileName}.", ".tmp")
            try {
                temp.writeText(text)
                setOwnerOnly(temp, "rw-------")
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        }

        /** Creates [dir], closed to everybody but its owner, or tightens an existing one. */
        private fun createPrivateDirectory(dir: Path) {
            Files.createDirectories(dir)
            setOwnerOnly(dir, "rwx------")
        }

        /** Gives [path] [permissions] (owner-only) where the file system has POSIX permissions. */
        private fun setOwnerOnly(
            path: Path,
            permissions: String,
        ) {
            if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
            }
        }

        private fun newSecret(): String {
            val bytes = ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
