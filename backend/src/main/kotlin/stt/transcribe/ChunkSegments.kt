package stt.transcribe

import stt.db.Segment

/**
 * The stored segments of chunk [chunkIdx]: whisper offsets shifted by [chunkStartMs] to absolute
 * positions in the original file, `seq` numbered from 0 in whisper order, not yet diarized.
 */
fun chunkSegments(
    chunkIdx: Int,
    chunkStartMs: Long,
    segments: List<WhisperSegment>,
): List<Segment> =
    segments.mapIndexed { seq, segment ->
        Segment(
            chunkIdx = chunkIdx,
            seq = seq,
            startMs = chunkStartMs + segment.startMs,
            endMs = chunkStartMs + segment.endMs,
            text = segment.text,
            speakerId = null,
        )
    }
