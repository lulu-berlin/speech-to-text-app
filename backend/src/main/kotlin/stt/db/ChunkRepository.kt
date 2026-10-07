package stt.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import stt.audio.ExportedChunk
import java.io.OutputStream
import java.nio.file.Files

class ChunkRepository(
    private val db: Database,
) {
    /**
     * Stores [taskId]'s chunk plan together with every chunk's FLAC in one write transaction (ADR 0024):
     * a chunk's `idx` is its position in [chunks], every chunk starts PENDING, and each FLAC is read
     * from its exported file, one file in memory at a time. Any failure (e.g. a missing file) stores nothing.
     */
    suspend fun storePlan(
        taskId: Long,
        chunks: List<ExportedChunk>,
    ) {
        db.writeTransaction {
            chunks.forEachIndexed { idx, chunk ->
                ChunksTable.insert {
                    it[ChunksTable.taskId] = taskId
                    it[ChunksTable.idx] = idx
                    it[startMs] = chunk.planned.startMs
                    it[endMs] = chunk.planned.endMs
                    it[status] = ChunkStatus.PENDING
                }
                ChunkAudioTable.insert {
                    it[ChunkAudioTable.taskId] = taskId
                    it[ChunkAudioTable.idx] = idx
                    it[flac] = ExposedBlob(Files.readAllBytes(chunk.file))
                }
            }
        }
    }

    /**
     * Writes chunk [idx]'s FLAC of task [taskId] to [target] (one BLOB, read on its own read-only
     * connection, which is closed before [target] is written); [target] is not closed.
     * Throws [NoSuchElementException] when the chunk has no stored FLAC.
     */
    suspend fun copyFlacTo(
        taskId: Long,
        idx: Int,
        target: OutputStream,
    ) {
        val flac =
            db.readTransaction {
                ChunkAudioTable
                    .select(ChunkAudioTable.flac)
                    .where { (ChunkAudioTable.taskId eq taskId) and (ChunkAudioTable.idx eq idx) }
                    .singleOrNull()
                    ?.get(ChunkAudioTable.flac)
                    ?.bytes
            } ?: throw NoSuchElementException("no stored FLAC for chunk $idx of task $taskId")
        withContext(Dispatchers.IO) { target.write(flac) }
    }

    /** The task's chunks in `idx` order. */
    suspend fun findByTask(taskId: Long): List<Chunk> =
        db.readTransaction {
            ChunksTable
                .selectAll()
                .where { ChunksTable.taskId eq taskId }
                .orderBy(ChunksTable.idx)
                .map { row ->
                    Chunk(
                        idx = row[ChunksTable.idx],
                        startMs = row[ChunksTable.startMs],
                        endMs = row[ChunksTable.endMs],
                        status = row[ChunksTable.status],
                    )
                }
        }
}
