package stt.db

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.OutputStream

/**
 * Reads a task's canonical clip (item 44) as the bytes its file was encoded to: the stored header row followed
 * by its one-second rows, in order, are the encoded FLAC byte for byte. Byte offsets are computed from the
 * stored parts, never stored, so playback serves the stored audio directly — no join, no re-encode, no cache.
 */
class CanonicalClipRepository(
    private val db: Database,
) {
    /** The clip's size in bytes (header + every second row), or 0 when the task has no canonical audio. */
    suspend fun size(taskId: Long): Long =
        db.readTransaction {
            // SQLite's length() of a BLOB is its byte count: the offsets are summed without reading a blob.
            val headerLength =
                AudioHeaderTable
                    .select(headerByteLength)
                    .where { AudioHeaderTable.taskId eq taskId }
                    .singleOrNull()
                    ?.get(headerByteLength)
                    ?: return@readTransaction 0L
            headerLength +
                AudioSecondTable
                    .select(rowByteLength)
                    .where { AudioSecondTable.taskId eq taskId }
                    .sumOf { it[rowByteLength] }
        }

    /**
     * Writes the clip's bytes `[from, to)` to [out], copying one stored part at a time, so neither a whole-file
     * nor a Range response ever holds more than one second (four frames) in heap.
     */
    suspend fun copyTo(
        taskId: Long,
        from: Long,
        to: Long,
        out: OutputStream,
    ) {
        if (to <= from) return
        db.readTransaction {
            var offset = 0L
            val header =
                AudioHeaderTable
                    .select(AudioHeaderTable.header)
                    .where { AudioHeaderTable.taskId eq taskId }
                    .singleOrNull()
                    ?.get(AudioHeaderTable.header)
                    ?.bytes
                    ?: return@readTransaction
            copyOverlap(out, header, offset, from, to)
            offset += header.size
            val rows =
                AudioSecondTable
                    .selectAll()
                    .where { AudioSecondTable.taskId eq taskId }
                    .orderBy(AudioSecondTable.second)
            for (row in rows) {
                if (offset >= to) break
                val frames = row[AudioSecondTable.bytes].bytes
                copyOverlap(out, frames, offset, from, to)
                offset += frames.size
            }
        }
    }

    /**
     * Writes the clip's header followed by its rows for seconds `[fromSecond, toSecond)` to [out]: a FLAC of that
     * run of whole seconds, which decodes on its own (item 44: a chunk's window is cut from it). One row in heap
     * at a time. Throws [NoSuchElementException] when the task has no stored clip.
     */
    suspend fun copySecondsTo(
        taskId: Long,
        fromSecond: Int,
        toSecond: Int,
        out: OutputStream,
    ) {
        db.readTransaction {
            val header =
                AudioHeaderTable
                    .select(AudioHeaderTable.header)
                    .where { AudioHeaderTable.taskId eq taskId }
                    .singleOrNull()
                    ?.get(AudioHeaderTable.header)
                    ?.bytes
                    ?: throw NoSuchElementException("task $taskId has no stored canonical clip")
            out.write(header)
            AudioSecondTable
                .selectAll()
                .where {
                    (AudioSecondTable.taskId eq taskId) and
                        (AudioSecondTable.second greaterEq fromSecond) and
                        (AudioSecondTable.second less toSecond)
                }.orderBy(AudioSecondTable.second)
                .forEach { row -> out.write(row[AudioSecondTable.bytes].bytes) }
        }
    }

    private companion object {
        /** `length(header)` / `length(bytes)`: SQLite's byte count of a BLOB, without materializing it. */
        val headerByteLength = CustomFunction("length", LongColumnType(), AudioHeaderTable.header)
        val rowByteLength = CustomFunction("length", LongColumnType(), AudioSecondTable.bytes)

        /** Writes [part]'s bytes that fall inside `[from, to)`, [part] starting at [offset] in the clip. */
        fun copyOverlap(
            out: OutputStream,
            part: ByteArray,
            offset: Long,
            from: Long,
            to: Long,
        ) {
            val start = maxOf(from, offset)
            val end = minOf(to, offset + part.size)
            if (start >= end) return
            out.write(part, (start - offset).toInt(), (end - start).toInt())
        }
    }
}
