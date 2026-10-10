import { useState } from 'react';
import type { ReactNode } from 'react';
import { css, styled } from 'styled-components';
import { languages } from '../../shared/api/fixtures';
import { SearchableSelect } from '../../shared/ui/SearchableSelect';
import { Page, Stack } from '../../shared/ui/layout';
import { PageHeading } from '../../shared/ui/typography';

// The upload options, one set for the whole batch (item 14): the language codes come
// from docs/api/languages.json (contract data, ADR 0029), speakers is auto/off/fixed with
// a count 1–20, max speakers 1–20 behind the advanced toggle.
const LANGUAGE_OPTIONS = languages.languages.map((language) => ({
  value: language.code,
  label: language.name,
}));

const SPEAKERS_CHOICES = ['auto', 'off', 'fixed'] as const;

export type SpeakersChoice = (typeof SPEAKERS_CHOICES)[number];

function isSpeakersChoice(value: string): value is SpeakersChoice {
  return (SPEAKERS_CHOICES as readonly string[]).includes(value);
}

// The shared look of the panel's text controls (selects and number inputs).
const controlStyle = css`
  padding: ${(props) => props.theme.spacing.sm} ${(props) => props.theme.spacing.md};
  border: ${(props) => props.theme.borderWidths.thin} solid ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.button};
  background: ${(props) => props.theme.colors.surface};
  color: ${(props) => props.theme.colors.text};
  font: inherit;
`;

const FieldBase = styled.div`
  display: flex;
  flex-direction: column;
  gap: ${(props) => props.theme.spacing.xs};
`;

const FieldLabel = styled.label`
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

const Select = styled.select`
  ${controlStyle}
`;

const NumberInput = styled.input`
  ${controlStyle}
`;

const AdvancedToggle = styled.button`
  padding: ${(props) => props.theme.spacing.xs} ${(props) => props.theme.spacing.sm};
  border: none;
  border-radius: ${(props) => props.theme.radii.button};
  background: transparent;
  color: ${(props) => props.theme.colors.accent};
  font: inherit;
  cursor: pointer;
`;

interface FieldProps {
  label: string;
  htmlFor: string;
  children: ReactNode;
}

// A labelled control of the upload options panel.
function Field({ label, htmlFor, children }: FieldProps) {
  return (
    <FieldBase>
      <FieldLabel htmlFor={htmlFor}>{label}</FieldLabel>
      {children}
    </FieldBase>
  );
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

export function QueueView() {
  const [language, setLanguage] = useState(languages.default);
  const [speakers, setSpeakers] = useState<SpeakersChoice>('auto');
  const [speakerCount, setSpeakerCount] = useState(2);
  const [maxSpeakers, setMaxSpeakers] = useState(8);
  const [advancedOpen, setAdvancedOpen] = useState(false);

  return (
    <Page>
      <Stack gap="lg">
        <PageHeading>Queue</PageHeading>
        <Stack gap="md">
          <Field label="Language" htmlFor="language">
            <SearchableSelect
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
                if (isSpeakersChoice(next)) {
                  setSpeakers(next);
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
                onChange={(event) => setSpeakerCount(clampCount(event.target.value, speakerCount))}
              />
            </Field>
          ) : null}
          <AdvancedToggle
            type="button"
            aria-expanded={advancedOpen}
            onClick={() => setAdvancedOpen((open) => !open)}
          >
            Advanced
          </AdvancedToggle>
          {advancedOpen ? (
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
          ) : null}
        </Stack>
      </Stack>
    </Page>
  );
}
