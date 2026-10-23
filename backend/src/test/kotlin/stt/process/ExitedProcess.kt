package stt.process

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * A process that has already exited with [exitCode]: its streams are empty and it is no longer alive. The specs
 * hand it to [ProcessRunner]'s `startProcess` seam when only the spawned command or the exit matters.
 */
internal class ExitedProcess(
    private val exitCode: Int = 0,
    private val pid: Long = 4242,
) : Process() {
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = exitCode

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean = true

    override fun exitValue(): Int = exitCode

    override fun destroy() = Unit

    override fun pid(): Long = pid

    override fun isAlive(): Boolean = false
}
