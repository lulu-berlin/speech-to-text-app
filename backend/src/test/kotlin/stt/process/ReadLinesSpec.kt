package stt.process

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

class ReadLinesSpec :
    BehaviorSpec({
        given("a child that writes its last line and exits between the drain's buffer check and its liveness check") {
            `when`("the drain reads the stream") {
                then("the last line is still delivered to the callback and the result") {
                    val pipe = LatePipe("last\n".toByteArray())
                    val child = ExitingChild(onIsAlive = pipe::write)
                    val delivered = mutableListOf<String>()

                    val lines = readLines(pipe, child) { delivered += it }

                    lines.shouldBe(listOf("last"))
                    delivered.shouldBe(listOf("last"))
                }
            }
        }

        given("a line far longer than one read chunk, with multi-byte characters split across chunk boundaries") {
            `when`("the drain reads it from an exited child") {
                then("the long line and the line after it are delivered intact") {
                    // 1 + 2 × 50,000 = 100,001 bytes: odd, so chunk boundaries fall inside 'ä' (2 bytes in UTF-8).
                    val longLine = "a" + "ä".repeat(50_000)
                    val pipe = LatePipe("$longLine\nnext\n".toByteArray())
                    pipe.write()
                    val delivered = mutableListOf<String>()

                    val lines = readLines(pipe, ExitingChild(onIsAlive = {})) { delivered += it }

                    lines.shouldBe(listOf(longLine, "next"))
                    delivered.shouldBe(listOf(longLine, "next"))
                }
            }
        }
    })

/** A pipe whose [bytes] are only written (become available) when [write] is called. */
private class LatePipe(
    bytes: ByteArray,
) : InputStream() {
    private val content = ByteArrayInputStream(bytes)
    private var written = false

    fun write() {
        written = true
    }

    override fun available(): Int = if (written) content.available() else 0

    override fun read(): Int = if (written) content.read() else -1

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = if (written) content.read(b, off, len) else -1
}

/** A direct child that is already dead when asked, having just run [onIsAlive] (its final write). */
private class ExitingChild(
    private val onIsAlive: () -> Unit,
) : Process() {
    override fun isAlive(): Boolean {
        onIsAlive()
        return false
    }

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    override fun destroy() {}
}
