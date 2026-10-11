-- Roadmap item 32: the CPU-fallback notice. When a task started with a chosen GPU that whisper-cli could not use,
-- the engine stores the device as cpu and names that GPU here (gpu:<index>); the Queue shows it as a global banner
-- until it is dismissed or a device is chosen again. NULL: no notice.
ALTER TABLE settings ADD COLUMN device_fallback TEXT CHECK (device_fallback IS NULL OR device_fallback LIKE 'gpu:%');
