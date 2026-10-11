package stt.config

/** Compute device for whisper-cli, from `STT_DEVICE` (port of oracle `parse_device`). */
sealed interface Device {
    /** whisper-cli picks the first GPU. */
    data object Auto : Device

    data object Cpu : Device

    /** An explicit GPU index; `gpu` is index 0. */
    data class Gpu(
        val index: Int,
    ) : Device
}

/**
 * The device in its stored and wire form (roadmap item 32): `auto`, `cpu`, or `gpu:<index>`; the old
 * `STT_DEVICE` spellings `gpu` and a bare index are written as `gpu:<index>` wherever a device is stored.
 */
fun Device.dbValue(): String =
    when (this) {
        Device.Auto -> "auto"
        Device.Cpu -> "cpu"
        is Device.Gpu -> "gpu:$index"
    }

/** Parses a stored or wire-form [raw] device (roadmap item 32); any other value fails fast (a corrupted settings row). */
fun parseStoredDevice(raw: String): Device =
    when {
        raw == "auto" -> {
            Device.Auto
        }

        raw == "cpu" -> {
            Device.Cpu
        }

        raw.startsWith("gpu:") -> {
            Device.Gpu(
                raw
                    .substringAfter("gpu:")
                    .toIntOrNull()
                    ?.takeIf { it >= 0 }
                    ?: throw IllegalArgumentException("stored device must be auto, cpu, or gpu:<index>, got \"$raw\""),
            )
        }

        else -> {
            throw IllegalArgumentException("stored device must be auto, cpu, or gpu:<index>, got \"$raw\"")
        }
    }
