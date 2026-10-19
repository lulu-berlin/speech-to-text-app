import { useMachine } from '@xstate/react';
import { styled } from 'styled-components';
import { languages } from '../../shared/api/fixtures';
import { toggleMachine } from '../../shared/state/toggleMachine';
import { controlStyle } from '../../shared/ui/controlStyle';
import { Field } from '../../shared/ui/Field';
import { Stack } from '../../shared/ui/layout';
import { SearchableSelect } from '../../shared/ui/SearchableSelect';
import { TextButton } from '../../shared/ui/TextButton';

// The upload options, one set for the whole batch (item 14, regrouped by item 59): the
// language codes come from docs/api/languages.json (contract data, ADR 0029), speakers is
// auto/off/fixed with a count 1–20, max speakers 1–20 behind the advanced toggle. They sit
// in a disclosure under the drop zone, so the action leads and the settings follow.
const LANGUAGE_OPTIONS = languages.languages.map((language) => ({
  value: language.code,
  label: language.name,
}));

const SPEAKERS_CHOICES = ['auto', 'off', 'fixed'] as const;

export type SpeakersChoice = (typeof SPEAKERS_CHOICES)[number];

function isSpeakersChoice(value: string): value is SpeakersChoice {
  return (SPEAKERS_CHOICES as readonly string[]).includes(value);
}

// The count inputs accept 1–20 only (item 12 contract): anything else is clamped, and a
// non-numeric value keeps the previous one.
function clampCount(raw: string, fallback: number): number {
  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed)) {
    return fallback;
  }
  return Math.min(20, Math.max(1, parsed));
}

const Select = styled.select`
  ${controlStyle}
`;

const NumberInput = styled.input`
  ${controlStyle}
`;

export interface UploadOptionsPanelProps {
  language: string;
  onLanguageChange: (language: string) => void;
  speakers: SpeakersChoice;
  onSpeakersChange: (speakers: SpeakersChoice) => void;
  speakerCount: number;
  onSpeakerCountChange: (count: number) => void;
  maxSpeakers: number;
  onMaxSpeakersChange: (count: number) => void;
}

export function UploadOptionsPanel({
  language,
  onLanguageChange,
  speakers,
  onSpeakersChange,
  speakerCount,
  onSpeakerCountChange,
  maxSpeakers,
  onMaxSpeakersChange,
}: UploadOptionsPanelProps) {
  const [disclosure, sendDisclosure] = useMachine(toggleMachine);
  const [advanced, sendAdvanced] = useMachine(toggleMachine);
  return (
    <Stack gap="md">
      <TextButton
        aria-expanded={disclosure.matches('open')}
        onClick={() => sendDisclosure({ type: 'TOGGLE' })}
      >
        Upload options
      </TextButton>
      {disclosure.matches('open') ? (
        <Stack gap="md">
          <Field label="Language" htmlFor="language">
            <SearchableSelect
              id="language"
              label="Language"
              options={LANGUAGE_OPTIONS}
              value={language}
              onChange={onLanguageChange}
            />
          </Field>
          <Field label="Speakers" htmlFor="speakers">
            <Select
              id="speakers"
              value={speakers}
              onChange={(event) => {
                const next = event.target.value;
                if (isSpeakersChoice(next)) {
                  onSpeakersChange(next);
                }
              }}
            >
              <option value="auto">auto</option>
              <option value="off">off</option>
              <option value="fixed">fixed</option>
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
                onChange={(event) =>
                  onSpeakerCountChange(clampCount(event.target.value, speakerCount))
                }
              />
            </Field>
          ) : null}
          <TextButton
            aria-expanded={advanced.matches('open')}
            onClick={() => sendAdvanced({ type: 'TOGGLE' })}
          >
            Advanced
          </TextButton>
          {advanced.matches('open') ? (
            <Field label="Max speakers" htmlFor="max-speakers">
              <NumberInput
                id="max-speakers"
                type="number"
                min={1}
                max={20}
                value={maxSpeakers}
                onChange={(event) =>
                  onMaxSpeakersChange(clampCount(event.target.value, maxSpeakers))
                }
              />
            </Field>
          ) : null}
        </Stack>
      ) : null}
    </Stack>
  );
}
