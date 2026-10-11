import { ErrorCallout } from '../../shared/ui/ErrorCallout';
import { TextButton } from '../../shared/ui/TextButton';

// The global CPU-fallback banner (item 32): the engine switched the chosen GPU to the CPU
// because whisper-cli could not use it. The notice survives reloads and restarts until it is
// dismissed — using the GPU again means choosing it on the Settings page. Alert, so a
// fallback while the user is looking at the queue is announced.
export function DeviceFallbackBanner({
  device,
  dismissing,
  onDismiss,
}: {
  device: string;
  dismissing: boolean;
  onDismiss: () => void;
}) {
  return (
    <ErrorCallout
      title={`${deviceLabel(device)} was unavailable`}
      detail="Transcription switched to the CPU. The device setting is now CPU; to use it again, choose it on the Settings page."
      action={
        <TextButton disabled={dismissing} onClick={onDismiss}>
          Dismiss
        </TextButton>
      }
    />
  );
}

// A device of the settings' value space (item 32: `auto`, `cpu`, `gpu:<index>`) as people
// read it: "Auto", "CPU", "GPU 1". An unknown value is shown as it is.
function deviceLabel(device: string): string {
  if (device === 'auto') {
    return 'Auto';
  }
  if (device === 'cpu') {
    return 'CPU';
  }
  const gpu = /^gpu:(\d+)$/.exec(device);
  return gpu === null ? device : `GPU ${gpu[1]}`;
}
