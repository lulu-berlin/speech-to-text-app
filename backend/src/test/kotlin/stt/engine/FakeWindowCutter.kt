package stt.engine

import stt.audio.WindowCutter
import java.nio.file.Files
import java.nio.file.Path

/** A [WindowCutter] that never spawns ffmpeg: it writes a stand-in input naming the cut, and records every call. */
internal class FakeWindowCutter : WindowCutter {
    /** One cut as the engine asked for it: the window file's bytes and the sample range. */
    data class Cut(
        val window: List<Byte>,
        val fromSample: Long,
        val toSample: Long,
    )

    /** Every cut, in call order. */
    val cuts = mutableListOf<Cut>()

    override suspend fun cut(
        window: Path,
        fromSample: Long,
        toSample: Long,
        target: Path,
    ) {
        cuts.add(Cut(Files.readAllBytes(window).toList(), fromSample, toSample))
        Files.writeString(target, "fake input: samples $fromSample..$toSample")
    }
}
