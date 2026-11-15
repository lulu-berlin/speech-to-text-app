type MessageFn = (id: string, values?: Record<string, string | number>) => string;

// A device of the settings' value space (item 32: `auto`, `cpu`, `gpu:<index>`) as people
// read it: "Auto", "CPU", "GPU 1". An offered GPU carries the probe's name ("GPU 1 — AMD …",
// item 45); without one the index alone is shown. An unknown value is shown as it is.
export function deviceLabel(
  device: string,
  t: MessageFn,
  gpuNames: Record<string, string> = {},
): string {
  if (device === 'auto') {
    return t('device.auto');
  }
  if (device === 'cpu') {
    return t('device.cpu');
  }
  const gpu = /^gpu:(\d+)$/.exec(device);
  if (gpu === null) {
    return device;
  }
  const index = gpu[1] ?? '';
  const name = gpuNames[device];
  return name === undefined ? t('device.gpu', { index }) : t('device.gpuNamed', { index, name });
}
