package stt.process

import java.io.IOException

/**
 * The named executable was not found on PATH (or as a path) before the run could start. It is an [IOException]:
 * a launch failure the caller may report instead of crashing (item 60's device probe).
 */
class ExecutableNotFoundException(
    message: String,
) : IOException(message)
