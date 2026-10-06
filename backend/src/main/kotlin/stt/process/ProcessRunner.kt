package stt.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProcessRunner {
    suspend fun run(
        command: List<String>,
        onStdoutLine: (String) -> Unit,
    ): ProcessResult =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command).start()
            val stdout = process.inputStream.bufferedReader().useLines { lines -> lines.onEach(onStdoutLine).toList() }
            ProcessResult(process.waitFor(), stdout)
        }
}
