package stt.transcribe

import stt.config.Device

/** whisper-cli 1.9.x device flags (port of oracle `device_args`): none = first GPU, `--no-gpu` = CPU, `--device N` = GPU N. */
fun deviceArgs(device: Device): List<String> =
    when (device) {
        Device.Auto -> emptyList()
        Device.Cpu -> listOf("--no-gpu")
        is Device.Gpu -> listOf("--device", device.index.toString())
    }
