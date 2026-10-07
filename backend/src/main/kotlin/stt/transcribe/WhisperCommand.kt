package stt.transcribe

import stt.config.Device
import java.nio.file.Path

/**
 * One whisper-cli run over [audio] (ADR 0004): JSON output to `<outputPrefix>.json` (`-oj -of`), progress
 * on stderr (`-pp`). `-t` only when [threads] is configured, as in oracle `transcribe_one`; `--prompt`
 * only when the previous chunk left a tail.
 */
fun whisperCommand(
    model: Path,
    audio: Path,
    language: String,
    threads: Int?,
    device: Device,
    outputPrefix: Path,
    prompt: String?,
    executable: String = "whisper-cli",
): List<String> =
    buildList {
        addAll(listOf(executable, "-m", model.toString(), "-f", audio.toString(), "-l", language))
        if (threads != null) addAll(listOf("-t", threads.toString()))
        addAll(deviceArgs(device))
        addAll(listOf("-oj", "-of", outputPrefix.toString(), "-pp"))
        if (prompt != null) addAll(listOf("--prompt", prompt))
    }
