import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { App } from '../../app/App';
import { lightTheme } from '../../shared/theme/theme';

function renderTranscript(taskId: string) {
  render(
    <MemoryRouter initialEntries={[`/history/${taskId}`]}>
      <App />
    </MemoryRouter>,
  );
  return screen.findByText('Live');
}

describe('the transcript view', () => {
  it('shows the speaker turns with timestamps, each speaker name in its own tint', async () => {
    await renderTranscript('3'); // task 3 is COMPLETED with speakersMode "fixed" (id 3).

    // Each turn's text (docs/api/turns.json) is visible.
    expect(
      await screen.findByText(/Good morning\. Thanks for joining us today\./),
    ).toBeInTheDocument();
    expect(screen.getByText('Happy to be here.')).toBeInTheDocument();
    expect(screen.getByText(/Let's start with your background\./)).toBeInTheDocument();

    // Each speaker's stored name is shown: turns.json lists "Speaker 1" and "Speaker 2",
    // and speaker 1 appears in two turns.
    const speakerNames = screen.getAllByText('Speaker 1');
    expect(speakerNames.length).toBeGreaterThan(0);
    const speakerOne = speakerNames[0]!;
    const speakerTwo = screen.getByText('Speaker 2');
    expect(speakerTwo).toBeInTheDocument();

    // Each speaker name carries its tint (speakerId mod palette size).
    expect(getComputedStyle(speakerOne).color).toBe(asRgb(lightTheme.colors.speakerTints[0]!));
    expect(getComputedStyle(speakerTwo).color).toBe(asRgb(lightTheme.colors.speakerTints[1]!));

    // A turn's timestamp is shown (as a time of day label).
    expect(screen.getByText(/00:00/)).toBeInTheDocument();
  });

  it('shows a speakers-off transcript without speaker labels', async () => {
    await renderTranscript('4'); // task 4 is FAILED with speakersMode "off" (id 4).

    // The turn text (docs/api/turns.speakers-off.json) is visible.
    expect(await screen.findByText('Remember to buy milk.')).toBeInTheDocument();
    expect(screen.getByText('And call the dentist on Monday.')).toBeInTheDocument();

    // No speaker name is shown; the timestamps still are.
    expect(screen.queryByText('Speaker 1')).not.toBeInTheDocument();
    expect(screen.queryByText(/Speaker/)).not.toBeInTheDocument();
    expect(screen.getByText(/00:00/)).toBeInTheDocument();
  });
});

// jsdom reports colours as rgb(); mirror the helper used by the other tests.
function asRgb(hex: string): string {
  const value = Number.parseInt(hex.slice(1), 16);
  return `rgb(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255})`;
}
