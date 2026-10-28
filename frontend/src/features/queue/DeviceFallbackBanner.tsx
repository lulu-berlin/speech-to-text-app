import { SettingsContext } from '../../app/SettingsContext';
import { ErrorCallout } from '../../shared/ui/ErrorCallout';
import { TextButton } from '../../shared/ui/TextButton';
import { deviceLabel } from '../../shared/ui/deviceLabel';
import { errorMessage } from '../../shared/errorMessage';

// The global CPU-fallback banner (item 32): the engine switched the chosen GPU to the CPU
// because whisper-cli could not use it. The notice survives reloads and restarts until it is
// dismissed — using the GPU again means choosing it on the Settings page. Alert, so a
// fallback while the user is looking at the queue is announced. It reads the app's one
// settings instance (item 72), so a settings-updated event reaches it without props, and it
// renders nothing while no notice is stored.
export function DeviceFallbackBanner() {
  const stored = SettingsContext.useSelector((state) => state.context.settings);
  const dismissError = SettingsContext.useSelector((state) => state.context.dismissError);
  // One operation at a time: a dismissal waits for a running write, and a write starts
  // after a running dismissal (the buttons are disabled meanwhile, item 72).
  const busy = SettingsContext.useSelector(
    (state) =>
      state.matches({ loaded: 'saving' }) ||
      state.matches({ loaded: 'confirming' }) ||
      state.matches({ loaded: 'dismissing' }),
  );
  const settingsActor = SettingsContext.useActorRef();
  if (stored?.deviceFallback == null) {
    return null;
  }
  return (
    <>
      <ErrorCallout
        title={`${deviceLabel(stored.deviceFallback, stored.gpuNames)} was unavailable`}
        detail="Transcription switched to the CPU. The device setting is now CPU; to use it again, choose it on the Settings page."
        action={
          <TextButton disabled={busy} onClick={() => settingsActor.send({ type: 'DISMISS' })}>
            Dismiss
          </TextButton>
        }
      />
      {dismissError !== null ? (
        <ErrorCallout title="Could not dismiss the notice" detail={errorMessage(dismissError)} />
      ) : null}
    </>
  );
}
