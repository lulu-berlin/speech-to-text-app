package stt.transcribe

import stt.config.Device
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Settings for runs against a stand-in whisper-cli, which ignores them. */
internal val TEST_SETTINGS = WhisperSettings(Path.of("/models/ggml-tiny.en.bin"), "en", threads = null, Device.Cpu)

/**
 * An executable script standing in for whisper-cli: prints [stderr] to stderr, writes [writes] (file to content)
 * if given, then exits with [exitCode].
 * The shebang names `sh` from PATH: neither `/bin/sh` nor `/usr/bin/env` exists in every environment (Nix).
 */
internal fun fakeWhisperCli(
    dir: Path,
    stderr: List<String>,
    exitCode: Int,
    writes: Pair<Path, String>? = null,
): Path {
    val script = dir.resolve("whisper-cli")
    val write = writes?.let { (file, content) -> "cat > '$file' <<'EOF'\n$content\nEOF\n" } ?: ""
    Files.writeString(script, "#!${shell()}\ncat >&2 <<'EOF'\n${stderr.joinToString("\n")}\nEOF\n${write}exit $exitCode\n")
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
    return script
}

private fun shell(): Path =
    System
        .getenv("PATH")
        .split(":")
        .map { Path.of(it, "sh") }
        .first { Files.isExecutable(it) }
