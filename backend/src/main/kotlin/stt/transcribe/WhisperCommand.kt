package stt.transcribe

import java.nio.file.Path

/**
 * One whisper-cli run over [audio] (ADR 0004): JSON output to `<outputPrefix>.json` (`-oj -of`), progress
 * on stderr (`-pp`). `-t` only when threads are configured; `--prompt`
 * only when the previous chunk left a tail.
 */
fun whisperCommand(
    settings: WhisperSettings,
    audio: Path,
    outputPrefix: Path,
    prompt: String?,
    executable: String = "whisper-cli",
): List<String> =
    buildList {
        addAll(listOf(executable, "-m", settings.model.toString(), "-f", audio.toString(), "-l", settings.language))
        if (settings.threads != null) addAll(listOf("-t", settings.threads.toString()))
        addAll(deviceArgs(settings.device))
        addAll(listOf("-oj", "-of", outputPrefix.toString(), "-pp"))
        if (prompt != null) addAll(listOf("--prompt", prompt))
    }

/** The file whisper-cli writes for `-oj -of [outputPrefix]`. */
fun jsonOutput(outputPrefix: Path): Path = Path.of("$outputPrefix.json")
