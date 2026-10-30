package stt.audio

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeout
import stt.Integration
import stt.db.AudioHeaderRepository
import stt.db.AudioSecondRepository
import stt.db.Database
import stt.db.TaskRepository
import stt.db.queuedTask
import stt.process.ProcessRunner
import java.io.BufferedOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The child JVM's max heap (item 71's recorded bound), measured 2026-10-30 on this clip (a ~38.5 MB canonical FLAC):
 * the whole-clip chunking before item 71 — the encode read whole, split into frame copies, joined again into row
 * copies — needs 84 MiB (out of memory at 80 MiB); the streaming store runs in 12 MiB (out of memory at 8 MiB,
 * the JVM's own baseline). 48 MiB lies between them, four times the streaming need.
 */
private const val HEAP_BOUND_MIB = 48

private const val CLIP_SECONDS = 20 * 60

class ChunkingHeapBoundIntegrationSpec :
    BehaviorSpec({
        tags(Integration)

        given("a generated 20-minute pseudo-random (incompressible) 16 kHz mono clip, uploaded as a task") {
            `when`("a child JVM limited to the recorded heap bound chunks it and stores the clip") {
                then("it completes, and the stored header and rows are the canonical encode, four frames per row") {
                    val workDir = tempdir("stt-heap-bound").toPath()
                    val dataDir = workDir.resolve("data")
                    val source = workDir.resolve("noise.wav")
                    writeNoiseWav(source, CLIP_SECONDS)
                    val taskId = Database.open(dataDir).use { db -> TaskRepository(db).insert(queuedTask("noise.wav", createdAt = 1L)) }

                    val child =
                        withTimeout(120.seconds) {
                            runChild(workDir, dataDir, taskId, source, Files.createDirectories(workDir.resolve("scratch")))
                        }

                    withClue({ "child JVM output:\n" + (child.stdout + child.stderr).joinToString("\n") }) {
                        child.exitCode shouldBe 0
                    }
                    val expected = workDir.resolve("expected.flac")
                    encodeCanonical(ProcessRunner(), source, expected)
                    Database.open(dataDir).use { db ->
                        val header = checkNotNull(AudioHeaderRepository(db).read(taskId)) { "no header stored" }
                        val rows = AudioSecondRepository(db).read(taskId)
                        // 1 200 s × 16 000 = 19 200 000 samples = 4 800 frames of 4 000: 1 200 rows of four frames,
                        // row k starting with frame 4k.
                        rows.map { it.second } shouldBe (0 until CLIP_SECONDS).toList()
                        rows.map { it.frames }.toSet() shouldBe setOf(4)
                        rows.map { frameNumber(it.bytes) } shouldBe (0 until CLIP_SECONDS).map { it * 4 }
                        val stored = MessageDigest.getInstance("SHA-256")
                        stored.update(header)
                        rows.forEach { stored.update(it.bytes) }
                        val encoded = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(expected))
                        stored.digest().toList() shouldBe encoded.toList()
                    }
                }
            }
        }
    })

/** Runs [ChunkUnderHeapBound] in a JVM limited to [HEAP_BOUND_MIB], on this JVM's classpath (an argument file). */
private suspend fun runChild(
    workDir: Path,
    dataDir: Path,
    taskId: Long,
    source: Path,
    scratchDir: Path,
): stt.process.ProcessResult {
    val classpath = System.getProperty("java.class.path").replace("\\", "\\\\").replace("\"", "\\\"")
    val argFile = workDir.resolve("child.args")
    Files.writeString(argFile, "-cp \"$classpath\"\n")
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    return ProcessRunner().run(
        listOf(
            java,
            "-Xmx${HEAP_BOUND_MIB}m",
            // The runs that must fail here run out of memory on purpose: never write a heap dump for them.
            "-XX:-HeapDumpOnOutOfMemoryError",
            "-XX:+ExitOnOutOfMemoryError",
            "@$argFile",
            ChunkUnderHeapBound::class.java.name,
            dataDir.toString(),
            taskId.toString(),
            source.toString(),
            scratchDir.toString(),
        ),
    )
}

/**
 * Writes [seconds] of 16 kHz mono 16-bit white noise as a WAV, streamed: every sample a uniformly random 16-bit
 * value from a fixed seed, so FLAC cannot compress it (the canonical clip is about as large as the PCM).
 */
private fun writeNoiseWav(
    target: Path,
    seconds: Int,
) {
    val dataBytes = seconds * SAMPLE_RATE * 2
    val header =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray())
            putInt(dataBytes)
        }
    val random = Random(71)
    val buffer = ByteArray(SAMPLE_RATE * 2)
    BufferedOutputStream(Files.newOutputStream(target)).use { out ->
        out.write(header.array())
        repeat(seconds) {
            random.nextBytes(buffer)
            out.write(buffer)
        }
    }
}

/**
 * The number of the FLAC frame [row] starts with: after the two sync bytes and the two code bytes, the number is
 * UTF-8-coded — one byte below 128, else a leading byte of n one-bits (n = the byte count) followed by n − 1
 * continuation bytes of six bits each. Fails when [row] does not start with a frame sync.
 */
private fun frameNumber(row: ByteArray): Int {
    check((row[0].toInt() and 0xFF) == 0xFF && (row[1].toInt() and 0xFE) == 0xF8) { "the row does not start with a frame" }
    val first = row[4].toInt() and 0xFF
    if (first < 0x80) return first
    val length = Integer.numberOfLeadingZeros((first xor 0xFF) shl 24)
    var value = first and (0xFF ushr (length + 1))
    for (i in 1 until length) value = (value shl 6) or (row[4 + i].toInt() and 0x3F)
    return value
}
