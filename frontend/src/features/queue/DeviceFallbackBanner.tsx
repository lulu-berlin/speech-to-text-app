import { useErrorText } from '../../shared/i18n/useErrorText';
import { useMessages } from '../../shared/i18n/useMessages';
import { useWriteControl } from '../../app/ConnectionContext';
import { SettingsContext } from '../../app/SettingsContext';
import { ErrorCallout } from '../../shared/ui/ErrorCallout';
import { TextButton } from '../../shared/ui/TextButton';
import { deviceLabel } from '../../shared/ui/deviceLabel';

// The global CPU-fallback banner (item 32): the engine switched the chosen GPU to the CPU
// because whisper-cli could not use it. The notice survives reloads and restarts until it is
// dismissed — using the GPU again means choosing it on the Settings page. Alert, so a
// fallback while the user is looking at the queue is announced. It reads the app's one
// settings instance (item 72), so a settings-updated event reaches it without props, and it
// renders nothing while no notice is stored.
export function DeviceFallbackBanner() {
  const t = useMessages();
  const errorText = useErrorText();
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
  const writes = useWriteControl();
  if (stored?.deviceFallback == null) {
    return null;
  }
  return (
    <>
      <ErrorCallout
        title={t('queue.fallback.title', {
          device: deviceLabel(stored.deviceFallback, t, stored.gpuNames),
        })}
        detail={t('queue.fallback.detail')}
        action={
          <TextButton
            disabled={busy || writes.disabled}
            title={writes.title}
            onClick={() => settingsActor.send({ type: 'DISMISS' })}
          >
            {t('queue.fallback.dismiss')}
          </TextButton>
        }
      />
      {dismissError !== null ? (
        <ErrorCallout title={t('queue.fallback.dismissFailed')} detail={errorText(dismissError)} />
      ) : null}
    </>
  );
}
