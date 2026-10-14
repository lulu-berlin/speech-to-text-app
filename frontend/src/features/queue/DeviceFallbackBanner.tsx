import { ErrorCallout } from '../../shared/ui/ErrorCallout';
import { TextButton } from '../../shared/ui/TextButton';
import { deviceLabel } from '../../shared/ui/deviceLabel';

// The global CPU-fallback banner (item 32): the engine switched the chosen GPU to the CPU
// because whisper-cli could not use it. The notice survives reloads and restarts until it is
// dismissed — using the GPU again means choosing it on the Settings page. Alert, so a
// fallback while the user is looking at the queue is announced.
export function DeviceFallbackBanner({
  device,
  gpuNames,
  dismissing,
  onDismiss,
}: {
  device: string;
  gpuNames: Record<string, string>;
  dismissing: boolean;
  onDismiss: () => void;
}) {
  return (
    <ErrorCallout
      title={`${deviceLabel(device, gpuNames)} was unavailable`}
      detail="Transcription switched to the CPU. The device setting is now CPU; to use it again, choose it on the Settings page."
      action={
        <TextButton disabled={dismissing} onClick={onDismiss}>
          Dismiss
        </TextButton>
      }
    />
  );
}
