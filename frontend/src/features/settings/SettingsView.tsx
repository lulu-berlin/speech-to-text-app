import { useEffect, useRef, useState } from 'react';
import type { ClipboardEvent } from 'react';
import styled from 'styled-components';
import { SettingsContext } from '../../app/SettingsContext';
import { languages } from '../../shared/api/fixtures';
import { ApiError } from '../../shared/api/types';
import type { SettingsUpdate, SpeakerMode } from '../../shared/api/types';
import { errorMessage } from '../../shared/errorMessage';
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog';
import { ErrorCallout } from '../../shared/ui/ErrorCallout';
import { Field } from '../../shared/ui/Field';
import { SearchableSelect } from '../../shared/ui/SearchableSelect';
import { TextButton } from '../../shared/ui/TextButton';
import { TextLink } from '../../shared/ui/TextLink';
import { controlStyle } from '../../shared/ui/controlStyle';
import { deviceLabel } from '../../shared/ui/deviceLabel';
import { Page, Row, Stack } from '../../shared/ui/layout';
import { PageHeading } from '../../shared/ui/typography';

const LANGUAGE_OPTIONS = languages.languages.map((language) => ({
  value: language.code,
  label: language.name,
}));

const SPEAKERS = ['auto', 'off', 'fixed'] as const;

// The form field each rejection code names (ADR 0062): the server's message shows there and
// that field goes back to its stored value. A code the form has no field for uses the page
// callout instead.
type SettingsField =
  'device' | 'threads' | 'language' | 'speakers' | 'speakerCount' | 'maxSpeakers';

const FIELD_BY_ERROR_CODE: Record<string, SettingsField | undefined> = {
  'invalid-device': 'device',
  'invalid-threads': 'threads',
  'invalid-language': 'language',
  'invalid-speakers': 'speakerCount',
  'invalid-max-speakers': 'maxSpeakers',
};

const Select = styled.select`
  ${controlStyle}
`;

const NumberInput = styled.input`
  ${controlStyle}
`;

// The polite live region that says a write is running (ADR 0062): a quieter line than the
// fields, announced when it appears and when it goes.
const SavingStatus = styled.p`
  margin: 0;
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

// Where Save used to sit: the reset action (ADR 0062).
const RestoreRow = styled(Row)`
  flex-wrap: wrap;
`;

// A field's inline danger line, referenced by the input's aria-describedby (item 72).
const FieldError = styled.p`
  margin: 0;
  color: ${(props) => props.theme.colors.danger};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

// The threads draft: null while empty (whisper's default), the count when it is a whole
// number >= 1 (the contract's bound), undefined when it is neither — invalid input, which is
// never sent. Number(), not parseInt: "1e5" is 100000, not 1.
function parseThreads(raw: string): number | null | undefined {
  if (raw === '') {
    return null;
  }
  const value = Number(raw);
  return Number.isInteger(value) && value >= 1 ? value : undefined;
}

// A speaker count draft: a whole number 1..20 (the contract's bound), undefined otherwise —
// no silent coercion into the range, since that would store a value nobody typed.
function parseCount(raw: string): number | undefined {
  const value = Number(raw);
  return Number.isInteger(value) && value >= 1 && value <= 20 ? value : undefined;
}

// A paste guard for a typed count: text that is not a valid value is refused whole, so the
// field keeps what it held; the caller's error stays shown until the next edit.
function guardPaste(
  event: ClipboardEvent<HTMLInputElement>,
  parse: (raw: string) => number | null | undefined,
  setRefused: (refused: boolean) => void,
): void {
  const text = event.clipboardData.getData('text');
  if (text === '' || parse(text) !== undefined) {
    setRefused(false);
    return;
  }
  event.preventDefault();
  setRefused(true);
}

// The Settings page (item 32): shows the stored settings and the devices to choose from.
// Every field saves itself (item 72, ADR 0062): there is no Save button, a change commits the
// settings as a whole with PUT /api/settings, and the machine's answer replaces the stored
// settings this view formats — unless a settings-updated arrived while it ran, which is
// newer and stands. It follows settings-updated events from another tab.
export function SettingsView() {
  // The app's one settings instance (item 72): the form is this view's draft, the stored
  // settings are shared with the queue and the fallback banner.
  const settings = SettingsContext.useSelector((state) => state);
  const settingsActor = SettingsContext.useActorRef();
  const [device, setDevice] = useState<string>('');
  // The typed fields hold the draft as text; the stored count is only ever what they commit.
  const [threads, setThreads] = useState('');
  const [speakerCount, setSpeakerCount] = useState('');
  const [maxSpeakers, setMaxSpeakers] = useState('');
  // Set when a paste was refused: the field keeps its draft and its error, and nothing is
  // written until the next edit (the user has been told the pasted count is not usable).
  const [threadsPasteRefused, setThreadsPasteRefused] = useState(false);
  const [countPasteRefused, setCountPasteRefused] = useState(false);
  const [maxPasteRefused, setMaxPasteRefused] = useState(false);
  const [language, setLanguage] = useState<string>('');
  const [speakers, setSpeakers] = useState<string>('');
  // Which field the user is in: a settings-updated keeps its draft (ADR 0062). A ref, so
  // entering or leaving a field never re-syncs the form by itself.
  const focusedField = useRef<SettingsField | null>(null);

  const stored = settings.context.settings;
  const writing = settings.matches({ loaded: 'saving' });
  const dismissing = settings.matches({ loaded: 'dismissing' });
  // Each typed field's parsed value, and whether it (or a refused paste) is invalid. Until
  // settings are stored the form is empty but shows no error (item 4 makes it inert).
  const threadsValue = parseThreads(threads);
  const speakerCountValue = parseCount(speakerCount);
  const maxSpeakersValue = parseCount(maxSpeakers);
  const threadsInvalid = stored !== null && (threadsValue === undefined || threadsPasteRefused);
  const speakerCountInvalid =
    stored !== null && (speakerCountValue === undefined || countPasteRefused);
  const maxSpeakersInvalid = stored !== null && (maxSpeakersValue === undefined || maxPasteRefused);
  // Nothing has a stored value to change yet: the form is inert until the load succeeds.
  const inert = stored === null;

  // The server's rejection names its field (ADR 0062): its message shows there, in place of a
  // local validation message, and that field goes back to its stored value. A code naming
  // none of the form's fields uses the page's callout instead.
  const rejection = settings.context.error instanceof ApiError ? settings.context.error : null;
  const rejectionField = rejection === null ? null : (FIELD_BY_ERROR_CODE[rejection.code] ?? null);
  const rejectionMessage = rejectionField === null ? null : (rejection?.message ?? null);
  // A field's error: the rejection message when the server named that field, otherwise the
  // field's own validation message (or none).
  const fieldError = (field: SettingsField, local: string | null): string | null =>
    rejectionField === field ? rejectionMessage : local;
  const deviceError = fieldError('device', null);
  const threadsError = fieldError(
    'threads',
    threadsInvalid ? 'Threads must be a whole number of 1 or more.' : null,
  );
  const languageError = fieldError('language', null);
  const speakersError = fieldError('speakers', null);
  const speakerCountError = fieldError(
    'speakerCount',
    speakerCountInvalid ? 'Speaker count must be a whole number from 1 to 20.' : null,
  );
  const maxSpeakersError = fieldError(
    'maxSpeakers',
    maxSpeakersInvalid ? 'Max speakers must be a whole number from 1 to 20.' : null,
  );

  // The fields whose draft is invalid right now: a settings-updated keeps their draft and
  // their error (ADR 0062).
  const invalidDraft = useRef<Set<SettingsField>>(new Set());
  invalidDraft.current = new Set([
    ...(threadsInvalid ? (['threads'] as const) : []),
    // `fixed` without a valid count is not a state to apply settings over: the mode and the
    // count keep the user's draft and the count's error.
    ...(speakerCountInvalid ? (['speakerCount'] as const) : []),
    ...(speakers === 'fixed' && speakerCountInvalid ? (['speakers'] as const) : []),
    ...(maxSpeakersInvalid ? (['maxSpeakers'] as const) : []),
  ]);

  // The stored settings the form was last synced from. While a write runs the form keeps
  // its drafts: an answer superseded by a settings-updated is not applied by the machine,
  // and the answer that starts a queued follow-up carries the user's older draft (ADR 0062).
  // Once writing ends, the stored settings sync — the event's, or the write's answer.
  const syncedSettings = useRef<typeof stored>(null);
  useEffect(() => {
    if (stored === null || stored === syncedSettings.current || writing) {
      return;
    }
    // Before the first sync every field is empty, which the count parsers read as invalid;
    // only a draft typed after the form was populated can keep an invalid value.
    const firstSync = syncedSettings.current === null;
    syncedSettings.current = stored;
    const keepsDraft = (field: SettingsField) =>
      focusedField.current === field || (!firstSync && invalidDraft.current.has(field));
    if (!keepsDraft('device')) {
      setDevice(stored.device);
    }
    if (!keepsDraft('threads')) {
      setThreads(stored.threads === null ? '' : String(stored.threads));
    }
    if (!keepsDraft('language')) {
      setLanguage(stored.defaultLanguage);
    }
    if (!keepsDraft('speakers')) {
      setSpeakers(stored.speakersMode);
    }
    if (!keepsDraft('speakerCount')) {
      setSpeakerCount(stored.speakersCount === null ? '' : String(stored.speakersCount));
    }
    if (!keepsDraft('maxSpeakers')) {
      setMaxSpeakers(String(stored.maxSpeakers));
    }
  }, [stored, writing]);

  // A rejected write reverts only the field its code names, to its last stored value; every
  // other draft stays as the user typed it (ADR 0062).
  useEffect(() => {
    if (stored === null || rejectionField === null) {
      return;
    }
    switch (rejectionField) {
      case 'device':
        setDevice(stored.device);
        break;
      case 'threads':
        setThreads(stored.threads === null ? '' : String(stored.threads));
        break;
      case 'language':
        setLanguage(stored.defaultLanguage);
        break;
      case 'speakers':
        setSpeakers(stored.speakersMode);
        break;
      case 'speakerCount':
        setSpeakerCount(stored.speakersCount === null ? '' : String(stored.speakersCount));
        break;
      case 'maxSpeakers':
        setMaxSpeakers(String(stored.maxSpeakers));
        break;
    }
  }, [rejectionField, settings.context.rejections, stored]);

  // The choices: the probe's cpu/auto/GPUs (availableDevices), plus the stored device and
  // the current draft when they are not among them, so the select can hold what the
  // settings carry or the user typed (an unknown value is still shown and sent, and the
  // server rejects it inline).
  const choices: Array<{ value: string; label: string }> = [];
  for (const value of [...(stored?.availableDevices ?? []), device, stored?.device] as Array<
    string | undefined
  >) {
    if (
      value !== undefined &&
      value !== null &&
      !choices.some((choice) => choice.value === value)
    ) {
      choices.push({ value, label: deviceLabel(value, stored?.gpuNames ?? {}) });
    }
  }

  // A change hands the machine the settings as a whole (the contract has no per-field write),
  // built from the form's draft; an invalid typed draft is never sent — the field's last
  // stored value travels instead, so a change to another field still stores. The machine
  // writes one at a time, coalesces the rest, and applies the server's answer or rejection.
  const commit = (change: Partial<SettingsUpdate>) => {
    // Nothing is written before the settings are stored: the form has no values to send yet.
    if (stored === null) {
      return;
    }
    const update: SettingsUpdate = {
      device,
      threads: threadsValue !== undefined ? threadsValue : stored.threads,
      defaultLanguage: language,
      speakersMode: speakers as SpeakerMode,
      speakersCount: speakers === 'fixed' ? (speakerCountValue ?? stored.speakersCount) : null,
      maxSpeakers: maxSpeakersValue ?? stored.maxSpeakers,
      ...change,
    };
    // `fixed` without a valid count is never a valid body (the server rejects
    // invalid-speakers): the stored speakers travel instead, so a change to another field
    // still stores while the count keeps its error (ADR 0062).
    if (
      update.speakersMode === 'fixed' &&
      (update.speakersCount === null ||
        !Number.isInteger(update.speakersCount) ||
        update.speakersCount < 1 ||
        update.speakersCount > 20)
    ) {
      update.speakersMode = stored.speakersMode;
      update.speakersCount = stored.speakersMode === 'fixed' ? stored.speakersCount : null;
    }
    settingsActor.send({ type: 'SAVE', settings: update });
  };

  // A rejected write that had a change waiting: rebuild that change from the form as it
  // stands now, with the rejected field's stored value in place of the draft the server
  // refused (ADR 0062). The machine drops the rejected snapshot; this is the follow-up.
  const recommit = settings.context.recommit;
  useEffect(() => {
    if (!recommit || stored === null || rejectionField === null) {
      if (recommit && stored !== null) {
        commit({});
      }
      return;
    }
    switch (rejectionField) {
      case 'device':
        commit({ device: stored.device });
        break;
      case 'threads':
        commit({ threads: stored.threads });
        break;
      case 'language':
        commit({ defaultLanguage: stored.defaultLanguage });
        break;
      case 'speakers':
        commit({
          speakersMode: stored.speakersMode,
          speakersCount: stored.speakersMode === 'fixed' ? stored.speakersCount : null,
        });
        break;
      case 'speakerCount':
        commit({ speakersMode: stored.speakersMode, speakersCount: stored.speakersCount });
        break;
      case 'maxSpeakers':
        commit({ maxSpeakers: stored.maxSpeakers });
        break;
    }
    // The machine clears `recommit` when it takes the SAVE, so this runs once.
  }, [recommit, rejectionField, stored]);

  // A typed count commits when the field itself loses focus or on Enter (ADR 0062), and only
  // when it is valid and differs from what is stored: typing and an unchanged draft write
  // nothing.
  const commitThreads = () => {
    if (!threadsInvalid && threadsValue !== stored?.threads) {
      commit({ threads: threadsValue });
    }
  };
  const commitSpeakerCount = () => {
    if (!speakerCountInvalid && speakerCountValue !== stored?.speakersCount) {
      commit({ speakersCount: speakerCountValue });
    }
  };
  const commitMaxSpeakers = () => {
    if (!maxSpeakersInvalid && maxSpeakersValue !== stored?.maxSpeakers) {
      commit({ maxSpeakers: maxSpeakersValue });
    }
  };

  return (
    <Page>
      <Stack gap="lg">
        <PageHeading>Settings</PageHeading>
        <Stack gap="md">
          <p>
            Where transcription runs and the defaults of new uploads. A saved change applies to the
            next task; one already running keeps its device.
          </p>
          {settings.matches({ loaded: 'saving' }) ? (
            <SavingStatus role="status">Saving…</SavingStatus>
          ) : null}
          {settings.context.error !== null && rejectionField === null ? (
            <ErrorCallout
              title={errorMessage(settings.context.error)}
              action={
                // Only the failed load retries: a rejected write is answered by fixing the
                // field, and RETRY is not handled in the loaded states.
                settings.matches('failed') ? (
                  <TextButton onClick={() => settingsActor.send({ type: 'RETRY' })}>
                    Retry
                  </TextButton>
                ) : undefined
              }
            />
          ) : null}
          {stored !== null && stored.deviceProbeError !== null ? (
            <ErrorCallout title="The device probe failed" detail={stored.deviceProbeError} />
          ) : null}
          <Field label="Device" htmlFor="device">
            <Select
              id="device"
              value={device}
              disabled={inert || dismissing}
              aria-invalid={deviceError !== null || undefined}
              aria-describedby={deviceError !== null ? 'device-error' : undefined}
              onChange={(event) => {
                const next = event.target.value;
                setDevice(next);
                commit({ device: next });
              }}
              onFocus={() => {
                focusedField.current = 'device';
              }}
              onBlur={() => {
                if (focusedField.current === 'device') {
                  focusedField.current = null;
                }
              }}
            >
              {choices.map((choice) => (
                <option key={choice.value} value={choice.value}>
                  {choice.label}
                </option>
              ))}
            </Select>
            {deviceError !== null ? <FieldError id="device-error">{deviceError}</FieldError> : null}
          </Field>
          <Field label="Threads" htmlFor="threads">
            <NumberInput
              id="threads"
              type="number"
              min={1}
              value={threads}
              disabled={inert || dismissing}
              aria-invalid={threadsError !== null || undefined}
              aria-describedby={threadsError !== null ? 'threads-error' : undefined}
              onFocus={() => {
                focusedField.current = 'threads';
              }}
              onChange={(event) => {
                setThreads(event.target.value);
                setThreadsPasteRefused(false);
              }}
              onBlur={() => {
                commitThreads();
                if (focusedField.current === 'threads') {
                  focusedField.current = null;
                }
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  commitThreads();
                }
              }}
              onPaste={(event) => guardPaste(event, parseThreads, setThreadsPasteRefused)}
            />
            {threadsError !== null ? (
              <FieldError id="threads-error">{threadsError}</FieldError>
            ) : null}
          </Field>
          <Field label="Default language" htmlFor="language">
            <SearchableSelect
              id="language"
              label="Language"
              options={LANGUAGE_OPTIONS}
              value={language}
              onChange={(next) => {
                setLanguage(next);
                commit({ defaultLanguage: next });
              }}
              disabled={inert || dismissing}
              invalid={languageError !== null}
              describedBy={languageError !== null ? 'language-error' : undefined}
              onFocus={() => {
                focusedField.current = 'language';
              }}
              onBlur={() => {
                if (focusedField.current === 'language') {
                  focusedField.current = null;
                }
              }}
            />
            {languageError !== null ? (
              <FieldError id="language-error">{languageError}</FieldError>
            ) : null}
          </Field>
          <Field label="Speakers" htmlFor="speakers">
            <Select
              id="speakers"
              value={speakers}
              disabled={inert || dismissing}
              aria-invalid={speakersError !== null || undefined}
              aria-describedby={speakersError !== null ? 'speakers-error' : undefined}
              onFocus={() => {
                focusedField.current = 'speakers';
              }}
              onBlur={() => {
                if (focusedField.current === 'speakers') {
                  focusedField.current = null;
                }
              }}
              onChange={(event) => {
                const next = event.target.value;
                if (!(SPEAKERS as readonly string[]).includes(next)) {
                  return;
                }
                const mode = next as SpeakerMode;
                setSpeakers(mode);
                if (mode === 'fixed') {
                  // The count exists only with fixed: until the field holds a valid count
                  // there is nothing valid to write, and its own commit then stores the mode.
                  if (speakerCountValue !== undefined) {
                    commit({ speakersMode: mode, speakersCount: speakerCountValue });
                  }
                  return;
                }
                commit({ speakersMode: mode, speakersCount: null });
              }}
            >
              {SPEAKERS.map((choice) => (
                <option key={choice} value={choice}>
                  {choice}
                </option>
              ))}
            </Select>
            {speakersError !== null ? (
              <FieldError id="speakers-error">{speakersError}</FieldError>
            ) : null}
          </Field>
          {speakers === 'fixed' ? (
            <Field label="Speaker count" htmlFor="speaker-count">
              <NumberInput
                id="speaker-count"
                type="number"
                min={1}
                max={20}
                value={speakerCount}
                disabled={inert || dismissing}
                aria-invalid={speakerCountError !== null || undefined}
                aria-describedby={speakerCountError !== null ? 'speaker-count-error' : undefined}
                onFocus={() => {
                  focusedField.current = 'speakerCount';
                }}
                onChange={(event) => {
                  setSpeakerCount(event.target.value);
                  setCountPasteRefused(false);
                }}
                onBlur={() => {
                  commitSpeakerCount();
                  if (focusedField.current === 'speakerCount') {
                    focusedField.current = null;
                  }
                }}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    commitSpeakerCount();
                  }
                }}
                onPaste={(event) => guardPaste(event, parseCount, setCountPasteRefused)}
              />
              {speakerCountError !== null ? (
                <FieldError id="speaker-count-error">{speakerCountError}</FieldError>
              ) : null}
            </Field>
          ) : null}
          <Field label="Max speakers" htmlFor="max-speakers">
            <NumberInput
              id="max-speakers"
              type="number"
              min={1}
              max={20}
              value={maxSpeakers}
              disabled={inert || dismissing}
              aria-invalid={maxSpeakersError !== null || undefined}
              aria-describedby={maxSpeakersError !== null ? 'max-speakers-error' : undefined}
              onFocus={() => {
                focusedField.current = 'maxSpeakers';
              }}
              onChange={(event) => {
                setMaxSpeakers(event.target.value);
                setMaxPasteRefused(false);
              }}
              onBlur={() => {
                commitMaxSpeakers();
                if (focusedField.current === 'maxSpeakers') {
                  focusedField.current = null;
                }
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  commitMaxSpeakers();
                }
              }}
              onPaste={(event) => guardPaste(event, parseCount, setMaxPasteRefused)}
            />
            {maxSpeakersError !== null ? (
              <FieldError id="max-speakers-error">{maxSpeakersError}</FieldError>
            ) : null}
          </Field>
          <RestoreRow>
            <TextButton
              disabled={inert || writing || dismissing}
              onClick={() => settingsActor.send({ type: 'ASK_RESET' })}
            >
              Restore default settings
            </TextButton>
          </RestoreRow>
          <p>
            <TextLink to="/queue">Back to the queue</TextLink>
          </p>
        </Stack>
      </Stack>
      <ConfirmDialog
        open={settings.matches({ loaded: 'confirming' })}
        title="Restore default settings?"
        confirmLabel="Restore"
        onConfirm={() => settingsActor.send({ type: 'CONFIRM_RESET' })}
        onCancel={() => settingsActor.send({ type: 'CANCEL_RESET' })}
      >
        Every field goes back to the server's defaults, and the CPU-fallback notice is cleared.
      </ConfirmDialog>
    </Page>
  );
}
