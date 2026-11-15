import { render, screen } from '@testing-library/react';
import { ThemeProvider } from 'styled-components';
import { LocaleProvider } from '../../app/LocaleProvider';
import { describe, expect, it } from 'vitest';
import { asRgb, px, required } from '../../test/dom';
import { renderThemed } from '../../test/render';
import { lightTheme } from '../theme/theme';
import { DiarizationProgressBar } from './DiarizationProgressBar';
import { ProgressBar } from './ProgressBar';

describe('the progress bars', () => {
  it('renders the transcription value and both diarization stages with tokens', () => {
    renderThemed(
      <>
        <ProgressBar value={0.67} label="Transcription" />
        <DiarizationProgressBar stage="EMBEDDING" progress={0.4} />
        <DiarizationProgressBar stage="CLUSTERING" progress={0.4} />
        <DiarizationProgressBar stage={null} progress={0} />
      </>,
    );

    const transcription = screen.getByRole('progressbar', { name: 'Transcription' });
    expect(transcription).toHaveAttribute('aria-valuenow', '67');
    const track = getComputedStyle(transcription);
    expect(track.height).toBe(px(lightTheme.spacing.sm));
    expect(track.backgroundColor).toBe(asRgb(lightTheme.colors.surface));
    expect(track.borderRadius).toBe(lightTheme.radii.pill);
    const fill = required(transcription.firstElementChild);
    expect(getComputedStyle(fill).width).toBe('67%');
    expect(getComputedStyle(fill).backgroundColor).toBe(asRgb(lightTheme.colors.accent));

    // The two-stage bar: the stage is the active half of the track.
    const diarization = screen.getAllByRole('progressbar', { name: 'Speaker diarization' });
    expect(diarization).toHaveLength(3);

    const embedding = required(diarization[0]);
    expect(embedding).toHaveAttribute('aria-valuenow', '20');
    expect(embedding).toHaveAttribute('aria-valuetext', 'Embedding 40%');
    expect(getComputedStyle(required(embedding.firstElementChild)).width).toBe('20%');

    const clustering = required(diarization[1]);
    expect(clustering).toHaveAttribute('aria-valuenow', '70');
    expect(clustering).toHaveAttribute('aria-valuetext', 'Clustering 40%');
    expect(getComputedStyle(required(clustering.firstElementChild)).width).toBe('70%');

    const pending = required(diarization[2]);
    expect(pending).toHaveAttribute('aria-valuenow', '0');

    // The midpoint divider marks the embedding/clustering boundary.
    const divider = required(required(diarization[0]).lastElementChild);
    expect(getComputedStyle(divider).left).toBe('50%');
  });

  it('shows the transcription percentage and the diarization stage label as visible values', () => {
    renderThemed(
      <>
        <ProgressBar value={0.67} label="Transcription" />
        <DiarizationProgressBar stage="EMBEDDING" progress={0.4} />
        <DiarizationProgressBar stage="CLUSTERING" progress={0.4} />
        <DiarizationProgressBar stage={null} progress={0} />
      </>,
    );

    // The transcription bar's value is the percentage as visible text to the right of
    // the track, in the small muted text style.
    const percentage = screen.getByText('67%');
    expect(getComputedStyle(percentage).color).toBe(asRgb(lightTheme.colors.textMuted));
    expect(getComputedStyle(percentage).fontSize).toBe(px(lightTheme.fontSizes.small));
    const transcription = screen.getByRole('progressbar', { name: 'Transcription' });
    expect(transcription.parentElement).toContainElement(percentage);

    // The diarization bar's value is its active stage label with the within-stage
    // percentage (progress within that stage, not the merged track value).
    expect(screen.getByText('Embedding 40%')).toBeInTheDocument();
    expect(screen.getByText('Clustering 40%')).toBeInTheDocument();

    // The value marks the track's stage when the task is not diarizing yet.
    const diarization = screen.getAllByRole('progressbar', { name: 'Speaker diarization' });
    expect(required(diarization[2]!).parentElement).not.toHaveTextContent('Embedding');
    expect(required(diarization[2]!).parentElement).not.toHaveTextContent('Clustering');

    // The value sits beside the track in one centered row.
    const row = required(transcription.parentElement);
    expect(getComputedStyle(row).display).toBe('flex');
    expect(getComputedStyle(row).alignItems).toBe('center');
  });
});

// The percentage follows the locale's number formatting (roadmap item 88, review fix 7): the
// queue phase already formats through Intl, and the bar's own copy must not append a bare "%".
describe('the progress bar percentage', () => {
  it("formats through the locale's Intl rules", () => {
    render(
      <ThemeProvider theme={lightTheme}>
        <LocaleProvider uiLocale="fr" locale="fr-FR">
          <ProgressBar value={0.42} label="Transcription" />
        </LocaleProvider>
      </ThemeProvider>,
    );
    const expected = new Intl.NumberFormat('fr-FR', { style: 'percent' }).format(0.42);
    const shown = screen.getByText(/42/);
    // The space before the sign is the locale's own (a narrow no-break space in French), so the
    // assertion compares the rendered text with Intl's output after normalising whitespace.
    expect(shown.textContent?.replace(/\s+/g, ' ')).toBe(expected.replace(/\s+/g, ' '));
    expect(shown.textContent).toMatch(/^42[\s\u00a0\u202f]%$/);
  });
});
