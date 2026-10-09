package stt.diarization

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Locale

/** Logger of the speaker summary (and of its failures); diagnostics only. */
internal val speakerSummaryLogger: Logger = LoggerFactory.getLogger("stt.diarization.SpeakerSummary")

/**
 * Logs per-speaker cohesion and cross-speaker confusion of the clustered rows (oracle `_print_speaker_summary`), at
 * INFO: per present speaker, ascending, `speaker {N}: {n} segments, within-cos {wc}` (`wc` = mean off-diagonal cosine
 * of its rows, 1.0 for a single row); with more than one speaker, `cluster-to-cluster cosine: ` and `{a}-{b} {mean
 * cross cosine}` per pair, joined by two spaces; numbers with three decimals, speakers 1-based. A low within-cos next
 * to high ones hints at two voices in one speaker; a high cross cosine at two voices clustering cannot separate.
 * [embeddings] are the clustered rows, [speakerIds] their final speakerIds; empty input fails, as in the oracle.
 */
internal fun logSpeakerSummary(
    embeddings: Array<FloatArray>,
    speakerIds: List<Int>,
) {
    val rowsOf = embeddings.indices.groupBy { speakerIds[it] }.toSortedMap()
    for ((speakerId, rows) in rowsOf) {
        val n = rows.size
        val withinCos = if (n > 1) meanDot(rows, rows, embeddings, skipSameRow = true) else 1.0
        speakerSummaryLogger.info("speaker ${speakerId + 1}: $n segments, within-cos ${threeDecimals(withinCos)}")
    }
    if (speakerIds.max() + 1 > 1) {
        val present = rowsOf.keys.toList()
        val pairs =
            present.flatMapIndexed { i, a ->
                present.drop(i + 1).map { b ->
                    "${a + 1}-${b + 1} ${threeDecimals(meanDot(rowsOf.getValue(a), rowsOf.getValue(b), embeddings, skipSameRow = false))}"
                }
            }
        speakerSummaryLogger.info("cluster-to-cluster cosine: " + pairs.joinToString("  "))
    }
}

/** Mean dot product over the row pairs of [rowsA] × [rowsB], leaving out a row with itself when [skipSameRow]. */
private fun meanDot(
    rowsA: List<Int>,
    rowsB: List<Int>,
    embeddings: Array<FloatArray>,
    skipSameRow: Boolean,
): Double {
    var sum = 0.0
    var count = 0
    for (a in rowsA) {
        for (b in rowsB) {
            if (skipSameRow && a == b) continue
            sum += dot(embeddings[a], embeddings[b])
            count++
        }
    }
    return sum / count
}

private fun threeDecimals(value: Double) = String.format(Locale.ROOT, "%.3f", value)
