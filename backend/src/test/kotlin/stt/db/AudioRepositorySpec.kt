package stt.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

private const val MIB = 1_048_576

class AudioRepositorySpec :
    BehaviorSpec({

        given("an audio stream of two full 1 MiB pages plus 12345 bytes") {
            `when`("it is written for a task and read back to a stream") {
                then("pages are stored, the bytes come back identical in page windows, size_bytes/sha256 match") {
                    withDatabase { db ->
                        val size = 2 * MIB + 12_345
                        val taskId = TaskRepository(db).insert(queuedTask("long.wav", createdAt = 1L))
                        val audio = AudioRepository(db)

                        audio.write(taskId, GeneratedBytes(size.toLong()))

                        val expectedSha256 = sha256Hex(GeneratedBytes(size.toLong()))
                        audio.metadata(taskId).shouldBe(AudioMetadata(sizeBytes = 2_109_497L, sha256 = expectedSha256))
                        partLengths(db, taskId).shouldBe(listOf(0 to MIB, 1 to MIB, 2 to 12_345))

                        val target = DigestingOutputStream()
                        audio.copyTo(taskId, target)
                        target.count.shouldBe(2_109_497L)
                        target.sha256Hex().shouldBe(expectedSha256)
                        target.largestWrite.shouldBeLessThanOrEqual(MIB)
                    }
                }
            }
        }
    })

/** Deterministic, non-repeating-per-page bytes generated on the fly; never materialized as a whole. */
private class GeneratedBytes(
    private val size: Long,
) : InputStream() {
    private var position = 0L

    private fun byteAt(i: Long): Int = ((i xor (i ushr 8) xor (i ushr 17)) * 131 and 0xFF).toInt()

    override fun read(): Int = if (position >= size) -1 else byteAt(position++)

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (position >= size) return -1
        val n = minOf(len.toLong(), size - position).toInt()
        for (k in 0 until n) b[off + k] = byteAt(position++).toByte()
        return n
    }
}

private class DigestingOutputStream : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set
    var largestWrite = 0
        private set

    override fun write(b: Int) {
        digest.update(b.toByte())
        count++
        largestWrite = maxOf(largestWrite, 1)
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        digest.update(b, off, len)
        count += len
        largestWrite = maxOf(largestWrite, len)
    }

    fun sha256Hex(): String = digest.digest().toHex()
}

private fun sha256Hex(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun partLengths(
    db: Database,
    taskId: Long,
): List<Pair<Int, Int>> =
    db.connection
        .prepareStatement("SELECT part_no, length(part) FROM audio_parts WHERE task_id = ? ORDER BY part_no")
        .use { st ->
            st.setLong(1, taskId)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getInt(1) to rs.getInt(2)) }
            }
        }
