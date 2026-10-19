package stt.audio

import stt.process.ProcessRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** A 16 kHz mono sine-wave FLAC `<name>.flac` in [dir], shaped like a stored chunk, for the real join. */
internal suspend fun sineFlac(
    dir: Path,
    name: String,
    frequency: String,
    seconds: String,
): Path =
    dir.resolve("$name.flac").also { flac ->
        val wav = dir.resolve("$name.wav")
        sineWav(wav, seconds.toDouble(), frequency.toDouble(), sampleRate = 16_000, channels = 1)
        val result =
            ProcessRunner().run(
                FFMPEG + listOf("-y", "-loglevel", "error", "-i", wav.toString(), "-c:a", "flac", flac.toString()),
            )
        result.expectSuccess("encode $wav to $flac")
        Files.delete(wav)
    }

/**
 * Writes [samples] of 16 kHz mono s16 noise to [target], zeroed inside each `(from, to)` second range of
 * [silences]. Deterministic, no ffmpeg: it also runs inside `runTest`, whose virtual clock would fire a timeout.
 */
internal suspend fun generateNoise(
    target: Path,
    samples: Int,
    silences: List<Pair<Double, Double>>,
) {
    val random = java.util.Random(1)
    val data =
        ShortArray(samples) { index ->
            val noise = (random.nextInt(32_768) - 16_384).toShort()
            val seconds = index / 16_000.0
            if (silences.any { (from, to) -> seconds >= from && seconds <= to }) 0 else noise
        }
    writeWav(target, 16_000, 1, data)
}

/**
 * The samples of [input] as raw little-endian 16-bit mono: ffmpeg decodes it with the enabled f32le muxer
 * into a scratch file under [scratchDir] that is removed again, and the floats are converted here.
 * [inputOptions] describe a raw input. No timeout of its own, as for [generateNoise].
 */
internal suspend fun toS16(
    input: Path,
    scratchDir: Path,
    vararg inputOptions: String,
): ByteArray {
    Files.createDirectories(scratchDir)
    val raw = scratchDir.resolve("${input.fileName}.${System.nanoTime()}.f32")
    val command =
        FFMPEG + listOf("-loglevel", "error", "-y") + inputOptions +
            listOf("-i", input.toString(), "-f", "f32le", "-ac", "1", raw.toString())
    ProcessRunner().run(command).expectSuccess("convert $input to raw f32le")
    val floats = ByteBuffer.wrap(Files.readAllBytes(raw)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val samples = ByteArray(floats.limit() * 2)
    for (index in 0 until floats.limit()) {
        val sample = Math.round(floats[index] * 32768.0).coerceIn(-32768L, 32767L).toInt()
        samples[index * 2] = (sample and 0xFF).toByte()
        samples[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }
    Files.delete(raw)
    return samples
}

/**
 * A stand-in canonical FLAC header declaring [totalSamples] samples, for fakes that must look like a stored clip
 * without encoding one: its only meaningful bytes are the 36-bit STREAMINFO total, at bit 108 of the block's data,
 * which is where [streamInfoTotalSamples] reads the clip's sample count.
 */
internal fun fakeCanonicalHeader(totalSamples: Int): ByteArray {
    val total = totalSamples.toLong()
    return "fake-canonical-header".toByteArray().copyOf(26).also { header ->
        header[21] = ((total shr 32) and 0x0F).toByte()
        header[22] = ((total shr 24) and 0xFF).toByte()
        header[23] = ((total shr 16) and 0xFF).toByte()
        header[24] = ((total shr 8) and 0xFF).toByte()
        header[25] = (total and 0xFF).toByte()
    }
}

/** The canonical clip's STREAMINFO view (ADR 0052): FLAC, 16-bit, 16 kHz, mono. */
internal val FLAC_16_BIT_16K_MONO =
    mapOf("codec_name" to "flac", "sample_fmt" to "s16", "sample_rate" to "16000", "channels" to "1")

/** The audio stream's codec, sample format, sample rate and channel count from its STREAMINFO. */
internal suspend fun streamInfo(file: Path): Map<String, String> {
    val info = flacStreamInfo(file)
    return mapOf(
        "codec_name" to "flac",
        "sample_fmt" to info.sampleFormat,
        "sample_rate" to info.sampleRate.toString(),
        "channels" to info.channels.toString(),
    )
}

/** A FLAC file's STREAMINFO and first audio frame, read from its bytes (no ffprobe). */
internal data class FlacStreamInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
    val audioStart: Int,
) {
    val sampleFormat: String
        get() = if (bitsPerSample == 16) "s16" else "s$bitsPerSample"
}

/**
 * Reads [file]'s STREAMINFO and the offset of its first audio frame. FLAC requires STREAMINFO as the
 * first metadata block; its 8 bytes at block offset 10 hold the 20-bit rate, 3-bit channels-1,
 * 5-bit bits-1 and 36-bit sample total. No ffprobe: the artifact does not ship it.
 */
internal fun flacStreamInfo(file: Path): FlacStreamInfo {
    val bytes = Files.readAllBytes(file)
    require(
        bytes.size >= 8 &&
            bytes[0] == 0x66.toByte() &&
            bytes[1] == 0x4C.toByte() &&
            bytes[2] == 0x61.toByte() &&
            bytes[3] == 0x43.toByte(),
    ) {
        "not a FLAC file: ${file.fileName}"
    }
    var offset = 4
    var sampleRate: Int? = null
    var channels: Int? = null
    var bits: Int? = null
    var total: Long? = null
    while (true) {
        require(offset + 4 <= bytes.size) { "truncated FLAC metadata: ${file.fileName}" }
        val header = bytes[offset].toInt() and 0xFF
        val last = header and 0x80 != 0
        val type = header and 0x7F
        val length =
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        val data = offset + 4
        require(data + length <= bytes.size) { "truncated FLAC metadata: ${file.fileName}" }
        require(offset != 4 || type == 0) { "FLAC's first metadata block is not STREAMINFO: ${file.fileName}" }
        if (type == 0) {
            require(length == 34) { "STREAMINFO is not 34 bytes: ${file.fileName}" }

            fun byte(index: Int) = bytes[data + index].toInt() and 0xFF
            sampleRate = (byte(10) shl 12) or (byte(11) shl 4) or (byte(12) shr 4)
            channels = ((byte(12) shr 1) and 0x07) + 1
            bits = (((byte(12) and 0x01) shl 4) or (byte(13) shr 4)) + 1
            total =
                ((byte(13).toLong() and 0x0F) shl 32) or
                (byte(14).toLong() shl 24) or
                (byte(15).toLong() shl 16) or
                (byte(16).toLong() shl 8) or
                byte(17).toLong()
        }
        offset = data + length
        if (last) break
    }
    return FlacStreamInfo(
        sampleRate = checkNotNull(sampleRate) { "FLAC without STREAMINFO: ${file.fileName}" },
        channels = checkNotNull(channels) { "FLAC without STREAMINFO: ${file.fileName}" },
        bitsPerSample = checkNotNull(bits) { "FLAC without STREAMINFO: ${file.fileName}" },
        totalSamples = checkNotNull(total) { "FLAC without STREAMINFO: ${file.fileName}" },
        audioStart = offset,
    )
}

/** Writes [samples] as a 16-bit PCM WAV at [sampleRate] and [channels]. */
internal fun writeWav(
    target: Path,
    sampleRate: Int,
    channels: Int,
    samples: ShortArray,
) {
    val dataBytes = samples.size * 2
    val header =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * 2)
            putShort((channels * 2).toShort())
            putShort(16.toShort())
            put("data".toByteArray())
            putInt(dataBytes)
        }
    val data = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { data.putShort(it) }
    Files.write(target, header.array() + data.array())
}

/**
 * Writes a sine of [seconds] at [frequency] with [amplitude] of full scale, zeroed inside each `(from, to)`
 * second range of [silences]; each of [channels] is divided by sqrt(channels), as ffmpeg's upmix does.
 */
internal fun toneWav(
    target: Path,
    seconds: Double,
    frequency: Double = 440.0,
    amplitude: Double = 0.5,
    sampleRate: Int = 44100,
    channels: Int = 1,
    silences: List<Pair<Double, Double>> = emptyList(),
) {
    val frames = (sampleRate * seconds).toInt()
    val scale = amplitude * Short.MAX_VALUE / Math.sqrt(channels.toDouble())
    val samples =
        ShortArray(frames * channels) { index ->
            val frame = index / channels
            val time = frame / sampleRate.toDouble()
            val value =
                if (silences.any { (from, to) -> time >= from && time <= to }) {
                    0.0
                } else {
                    Math.sin(2.0 * Math.PI * frequency * time) * scale
                }
            value.toInt().toShort()
        }
    writeWav(target, sampleRate, channels, samples)
}

/** Writes a sine of [seconds] at [frequency]: 1/8 of full scale. */
internal fun sineWav(
    target: Path,
    seconds: Double,
    frequency: Double = 440.0,
    sampleRate: Int = 44100,
    channels: Int = 1,
) = toneWav(target, seconds, frequency, amplitude = 1.0 / 8.0, sampleRate = sampleRate, channels = channels)

/** Writes a WAV with no samples, at [sampleRate] and [channels]. */
internal fun emptyWav(
    target: Path,
    sampleRate: Int = 44100,
    channels: Int = 2,
) = writeWav(target, sampleRate, channels, ShortArray(0))
