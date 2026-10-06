package stt.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.InputStream

class ProcessRunner {
    suspend fun run(
        command: List<String>,
        onStdoutLine: (String) -> Unit = {},
    ): ProcessResult =
        coroutineScope {
            val process = withContext(Dispatchers.IO) { ProcessBuilder(command).start() }
            val stdout = async(Dispatchers.IO) { readLines(process.inputStream, onStdoutLine) }
            val stderr = async(Dispatchers.IO) { readLines(process.errorStream) {} }
            val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
            ProcessResult(exitCode, stdout.await(), stderr.await())
        }

    private fun readLines(
        stream: InputStream,
        onLine: (String) -> Unit,
    ): List<String> =
        stream.bufferedReader().useLines { lines ->
            lines.onEach(onLine).toList()
        }
}
