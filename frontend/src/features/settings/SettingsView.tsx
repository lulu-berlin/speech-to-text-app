import { useMachine } from '@xstate/react';
import { useEffect, useState } from 'react';
import styled from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import { languages } from '../../shared/api/fixtures';
import { saveSettings } from '../../shared/api/client';
import type { SpeakerMode } from '../../shared/api/types';
import { ErrorCallout } from '../../shared/ui/ErrorCallout';
import { Field } from '../../shared/ui/Field';
import { SearchableSelect } from '../../shared/ui/SearchableSelect';
import { TextButton } from '../../shared/ui/TextButton';
import { TextLink } from '../../shared/ui/TextLink';
import { controlStyle } from '../../shared/ui/controlStyle';
import { Page, Row, Stack } from '../../shared/ui/layout';
import { PageHeading } from '../../shared/ui/typography';
import { settingsMachine } from '../../shared/state/settingsMachine';

const LANGUAGE_OPTIONS = languages.languages.map((language) => ({
  value: language.code,
  label: language.name,
}));

const SPEAKERS = ['auto', 'off', 'fixed'] as const;

const Select = styled.select`
  ${controlStyle}
`;

const NumberInput = styled.input`
  ${controlStyle}
`;

const SaveRow = styled(Row)`
  flex-wrap: wrap;
`;

// A count 1..20 from an input: a non-numeric or empty value stays the previous one,
// anything else is clamped into the range (the server's bound, item 12).
function clampCount(raw: string, fallback: number): number {
  const parsed = Number.parseInt(raw);
  if (Number.isNaN(parsed)) {
    return fallback;
  }
  return Math.min(20, Math.max(1, parsed));
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

// The Settings page (item 32): shows the stored settings and the devices to choose from,
// saves a change with PUT /api/settings (a rejection shows inline, restores the stored
// settings to the form, and changes nothing on the server), and follows settings-updated
// events from another tab. A saved change applies to the next task; the machine's 200
// replaces the stored settings this view formats.
export function SettingsView() {
  const settingsUpdate = ConnectionContext.useSelector((state) => state.context.settingsUpdate);
  const [settings, sendSettings] = useMachine(settingsMachine);
  const [device, setDevice] = useState<string>('');
  // '' while no thread count is stored; a number otherwise.
  const [threads, setThreads] = useState('');
  const [language, setLanguage] = useState<string>('');
  const [speakers, setSpeakers] = useState<string>('');
  const [speakerCount, setSpeakerCount] = useState(2);
  const [maxSpeakers, setMaxSpeakers] = useState(8);

  // Follow the live stream (item 32): another tab's write updates this tab's settings.
  useEffect(() => {
    if (settingsUpdate !== null) {
      sendSettings({ type: 'UPDATED', update: settingsUpdate });
    }
  }, [settingsUpdate, sendSettings]);

  // Sync the form with the stored settings, which changed (fetch, another tab's update,
  // this tab's save response) or after a rejected save, whose counter bumped while the
  // settings are still the stored ones (the rejection restored nothing).
  const stored = settings.context.settings;
  useEffect(() => {
    if (stored === null) {
      return;
    }
    setDevice(stored.device);
    setThreads(stored.threads === null ? '' : String(stored.threads));
    setLanguage(stored.defaultLanguage);
    setSpeakers(stored.speakersMode);
    if (stored.speakersCount !== null) {
      setSpeakerCount(stored.speakersCount);
    }
    setMaxSpeakers(stored.maxSpeakers);
  }, [stored, settings.context.rejections]);

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
      choices.push({ value, label: deviceLabel(value) });
    }
  }

  const dismissing = settings.matches({ loaded: 'dismissing' });

  const save = async () => {
    const update = {
      device,
      threads: threads === '' ? null : Number.parseInt(threads),
      defaultLanguage: language,
      speakersMode: speakers as SpeakerMode,
      speakersCount: speakers === 'fixed' ? speakerCount : null,
      maxSpeakers,
    };
    try {
      // The machine applies the stored answer (its notice is cleared and its own choices
      // kept); the settings-updated event carries the same write to other tabs.
      sendSettings({ type: 'SAVE_DONE', settings: await saveSettings(update), error: null });
    } catch (error) {
      sendSettings({ type: 'SAVE_DONE', settings: update, error });
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
          {settings.context.error !== null ? (
            <ErrorCallout title={errorMessage(settings.context.error)} />
          ) : null}
          <Field label="Device" htmlFor="device">
            <Select id="device" value={device} onChange={(event) => setDevice(event.target.value)}>
              {choices.map((choice) => (
                <option key={choice.value} value={choice.value}>
                  {choice.label}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Threads" htmlFor="threads">
            <NumberInput
              id="threads"
              type="number"
              min={1}
              value={threads}
              onChange={(event) => setThreads(event.target.value)}
            />
          </Field>
          <Field label="Default language" htmlFor="language">
            <SearchableSelect
              id="language"
              label="Language"
              options={LANGUAGE_OPTIONS}
              value={language}
              onChange={setLanguage}
            />
          </Field>
          <Field label="Speakers" htmlFor="speakers">
            <Select
              id="speakers"
              value={speakers}
              onChange={(event) => {
                const next = event.target.value;
                if ((SPEAKERS as readonly string[]).includes(next)) {
                  setSpeakers(next);
                }
              }}
            >
              {SPEAKERS.map((choice) => (
                <option key={choice} value={choice}>
                  {choice}
                </option>
              ))}
            </Select>
          </Field>
          {speakers === 'fixed' ? (
            <Field label="Speaker count" htmlFor="speaker-count">
              <NumberInput
                id="speaker-count"
                type="number"
                min={1}
                max={20}
                value={speakerCount}
                onChange={(event) => setSpeakerCount(clampCount(event.target.value, speakerCount))}
              />
            </Field>
          ) : null}
          <Field label="Max speakers" htmlFor="max-speakers">
            <NumberInput
              id="max-speakers"
              type="number"
              min={1}
              max={20}
              value={maxSpeakers}
              onChange={(event) => setMaxSpeakers(clampCount(event.target.value, maxSpeakers))}
            />
          </Field>
          <SaveRow>
            <TextButton disabled={dismissing} onClick={() => void save()}>
              Save
            </TextButton>
          </SaveRow>
          <p>
            <TextLink to="/queue">Back to the queue</TextLink>
          </p>
        </Stack>
      </Stack>
    </Page>
  );
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
