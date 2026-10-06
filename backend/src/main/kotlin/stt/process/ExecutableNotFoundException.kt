package stt.process

/** The named executable was not found on PATH (or as a path) before the run could start. */
class ExecutableNotFoundException(
    message: String,
) : RuntimeException(message)
