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
