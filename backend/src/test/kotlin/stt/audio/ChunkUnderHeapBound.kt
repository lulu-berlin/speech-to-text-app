package stt.audio

import kotlinx.coroutines.runBlocking
import stt.db.ChunkRepository
import stt.db.Database
import stt.process.ProcessRunner
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Path

/**
 * The child JVM of `ChunkingHeapBoundIntegrationSpec` (item 71): runs the engine's CHUNKING step — chunk the
 * source, store its plan and canonical clip — for one task of the database in a data directory, under the heap
 * bound the parent starts it with. Exits 0 when the clip is stored; an `OutOfMemoryError` ends the JVM with a
 * non-zero status (the parent passes `-XX:+ExitOnOutOfMemoryError`). Prints the peak heap use it saw last.
 *
 * Arguments: the data directory, the task id, the source audio file, the chunker's scratch directory.
 */
object ChunkUnderHeapBound {
    @JvmStatic
    fun main(args: Array<String>) {
        val (dataDir, taskId, source, scratchDir) = args
        runBlocking {
            Database.open(Path.of(dataDir)).use { db ->
                val chunked = AudioChunker(ProcessRunner()).chunk(Path.of(source), Path.of(scratchDir))
                ChunkRepository(db).storeChunked(taskId.toLong(), chunked)
            }
        }
        val peakHeap =
            ManagementFactory
                .getMemoryPoolMXBeans()
                .filter { it.type == MemoryType.HEAP }
                .sumOf { it.peakUsage.used }
        println("peak heap used: ${peakHeap / (1024 * 1024)} MiB")
    }
}
