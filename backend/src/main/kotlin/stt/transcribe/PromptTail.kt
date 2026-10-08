package stt.transcribe

import stt.db.Segment

/** Characters of the previous chunk's text passed as `--prompt`: well under whisper's n_text_ctx/2 token limit. */
private const val PROMPT_TAIL_CHARS = 200

/**
 * The `--prompt` for the chunk after [previousChunk] (ADR 0004): its text joined and trimmed, kept whole up to
 * [PROMPT_TAIL_CHARS]; longer text keeps its last [PROMPT_TAIL_CHARS] characters without a partial leading word
 * (dropped up to the first whitespace character). Null when the previous chunk has no text.
 */
fun promptTail(previousChunk: List<Segment>): String? {
    val text = previousChunk.joinToString("") { it.text }.trim()
    if (text.length <= PROMPT_TAIL_CHARS) return text.ifEmpty { null }
    val cut = text.length - PROMPT_TAIL_CHARS
    val tail = text.substring(cut)
    val cutsWord = !text[cut - 1].isWhitespace() && !tail[0].isWhitespace()
    return (if (cutsWord) tail.dropWhile { !it.isWhitespace() } else tail).trim().ifEmpty { null }
}
