// A device of the settings' value space (item 32: `auto`, `cpu`, `gpu:<index>`) as people
// read it: "Auto", "CPU", "GPU 1". An offered GPU carries the probe's name ("GPU 1 — AMD …",
// item 45); without one the index alone is shown. An unknown value is shown as it is.
export function deviceLabel(device: string, gpuNames: Record<string, string> = {}): string {
  if (device === 'auto') {
    return 'Auto';
  }
  if (device === 'cpu') {
    return 'CPU';
  }
  const gpu = /^gpu:(\d+)$/.exec(device);
  if (gpu === null) {
    return device;
  }
  const name = gpuNames[device];
  return name === undefined ? `GPU ${gpu[1]}` : `GPU ${gpu[1]} — ${name}`;
}
