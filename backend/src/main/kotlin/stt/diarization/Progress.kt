package stt.diarization

/** Progress of a pausable diarization step: called with `(steps done, total steps)` after each step. */
internal typealias Progress = (done: Int, total: Int) -> Unit
