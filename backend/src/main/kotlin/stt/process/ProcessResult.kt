package stt.process

data class ProcessResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
)
