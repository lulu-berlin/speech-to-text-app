package stt.diarization

/** One UPGMA merge: cluster [j] joins cluster [i] (`i < j`, original row indices) at cosine distance [height]. */
internal data class Merge(
    val i: Int,
    val j: Int,
    val height: Double,
)
