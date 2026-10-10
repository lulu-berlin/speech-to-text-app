import { fireEvent, render, screen } from '@testing-library/react';
import { http } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { App } from '../../app/App';
import { lightTheme } from '../../shared/theme/theme';
import { eventStreamResponse } from '../../shared/api/handlers';
import { taskCanceled, taskFailed } from '../../shared/api/fixtures';
import { server } from '../../test/server';

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

    // Each turn's text is visible; turns are rendered per segment so that the playhead can
    // highlight one, and the segments of a turn join into its text (docs/api/turns.json).
    expect(await screen.findByText('Good morning.')).toBeInTheDocument();
    expect(screen.getByText('Thanks for joining us today.')).toBeInTheDocument();
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
    // Mock addition: the snapshot's task 4 is COMPLETED with speakersMode "off" (the
    // default snapshot has it FAILED, which now shows a status page). The /turns handler
    // serves turns.speakers-off.json for id 4's off mode.
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          [
            'snapshot',
            {
              tasks: [{ ...taskFailed, id: 4, status: 'COMPLETED', speakersMode: 'off' }],
              queue: { paused: false, taskIds: [] },
            },
          ],
        ]),
      ),
    );
    await renderTranscript('4');

    // The turn text (docs/api/turns.speakers-off.json) is visible.
    expect(await screen.findByText('Remember to buy milk.')).toBeInTheDocument();
    expect(screen.getByText('And call the dentist on Monday.')).toBeInTheDocument();

    // No speaker name is shown; the timestamps still are.
    expect(screen.queryByText('Speaker 1')).not.toBeInTheDocument();
    expect(screen.queryByText(/Speaker/)).not.toBeInTheDocument();
    expect(screen.getByText(/00:00/)).toBeInTheDocument();
  });

  it('links each download button to the transcript in its format', async () => {
    await renderTranscript('3'); // task 3 is COMPLETED (id 3).

    for (const format of ['txt', 'srt', 'vtt'] as const) {
      const link = screen.getByRole('link', { name: `Download transcript (${format})` });
      expect(link).toHaveAttribute('href', `/api/tasks/3/transcript?format=${format}`);
    }
  });

  it('points the audio player at the task audio when the task has audio', async () => {
    await renderTranscript('3'); // task 3 is COMPLETED with hasAudio true.
    const player = screen.getByLabelText('Audio for interview.mp3');
    expect(player.tagName).toBe('AUDIO');
    expect(player).toHaveAttribute('src', '/api/tasks/3/audio');
  });

  it('shows no player but a note when the task has no audio', async () => {
    await renderTranscript('7'); // task 7 (QUEUED) has hasAudio false.
    expect(screen.queryByLabelText(/Audio/)).not.toBeInTheDocument();
    expect(screen.getByText(/has no audio/i)).toBeInTheDocument();
  });

  it('highlights the segment containing the playhead, nothing in a gap, and moves on seek', async () => {
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const activeSegment = () => document.querySelector('[data-active]');

    // turns.json segments: turn 1 [0,2100) + [2400,5200); turn 2 [5800,9100);
    // turn 3 [9600,14250); gaps at 2100-2400 and 5200-5800.

    // Inside the first segment.
    audio.currentTime = 1.0; // 1000 ms
    fireEvent(audio, new Event('timeupdate'));
    expect(activeSegment()?.textContent).toContain('Good morning.');

    // In the gap after the first turn's first segment: nothing is highlighted.
    audio.currentTime = 2.25; // 2250 ms, between 2100 and 2400
    fireEvent(audio, new Event('timeupdate'));
    expect(activeSegment()).toBeNull();

    // Inside the second turn.
    audio.currentTime = 6.0; // 6000 ms, inside [5800,9100)
    fireEvent(audio, new Event('timeupdate'));
    expect(activeSegment()?.textContent).toContain('Happy to be here.');

    // Seeking moves the highlight to the new position.
    audio.currentTime = 3.0; // 3000 ms, inside [2400,5200)
    fireEvent(audio, new Event('seeked'));
    expect(activeSegment()?.textContent).toContain('Thanks for joining us today.');
  });

  it('clicks a segment to seek the player to its start and play', async () => {
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());

    // Click the "Thanks for joining us today." segment (starts at 2400 ms).
    fireEvent.click(screen.getByRole('button', { name: /Thanks for joining us today\./ }));

    // The player sought to the segment's start (2400 ms) and played.
    expect(audio.currentTime).toBe(2.4);
    expect(play).toHaveBeenCalledTimes(1);

    // The highlight moved to the sought segment.
    expect(document.querySelector('[data-active]')?.textContent).toContain(
      'Thanks for joining us today.',
    );
  });

  it('keeps the highlighted segment in view while playing, stops on a hand scroll, and resumes', async () => {
    // jsdom has no scrollIntoView; the component calls it, so stub it on the prototype.
    Element.prototype.scrollIntoView = vi.fn();
    const scrollIntoView = vi.spyOn(
      Element.prototype as never,
      'scrollIntoView',
    ) as unknown as ReturnType<typeof vi.fn>;
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const region = screen.getByRole('region', { name: /transcript/i });

    // While playing, the active segment is scrolled into view (follow mode).
    audio.currentTime = 1.0;
    fireEvent(audio, new Event('timeupdate'));
    expect(scrollIntoView).toHaveBeenCalledTimes(1);

    // A hand scroll stops following: a "Back to playing position" button appears. (The
    // programmatic follow-scroll's hold-off clears on a macrotask, so the test waits a tick
    // for a hand scroll to arrive on its own, as it would in a browser.)
    const { promise: tick, resolve: resolveTick } = Promise.withResolvers<void>();
    setTimeout(resolveTick, 0);
    await tick;
    fireEvent.scroll(region);
    expect(screen.getByRole('button', { name: /back to playing position/i })).toBeInTheDocument();

    // While stopped, playback no longer scrolls.
    scrollIntoView.mockClear();
    audio.currentTime = 6.0;
    fireEvent(audio, new Event('timeupdate'));
    expect(scrollIntoView).not.toHaveBeenCalled();

    // "Back to playing position" resumes following and scrolls to the current segment.
    fireEvent.click(screen.getByRole('button', { name: /back to playing position/i }));
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
  });

  it('shows a status page for a failed task: what happened, error, retry, delete, no transcript', async () => {
    await renderTranscript('4'); // task 4 is FAILED in the snapshot (id 4).

    // No transcript: no speaker name or segment text.
    expect(screen.queryByText('Speaker 1')).not.toBeInTheDocument();
    expect(screen.queryByText(/Listening|Good morning/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Download transcript/)).not.toBeInTheDocument();

    // What happened: the phase (TRANSCRIBING -> "transcribing") and transcribedUntilMs.
    expect(await screen.findByText(/failed while transcribing at/i)).toBeInTheDocument();
    // transcribedUntilMs 298650 -> 04:58
    expect(screen.getByText(/04:58/)).toBeInTheDocument();

    // The error message, Retry and Delete controls, and (hasAudio) the player.
    expect(screen.getByText(/failed to allocate memory/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.getByLabelText('Audio for call.ogg')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: /transcript/i })).not.toBeInTheDocument();
  });

  it('shows a status page for a canceled task with no error and no transcript', async () => {
    // Mock addition: the snapshot's task 5 is CANCELED (the default snapshot has it RUNNING),
    // so the canceled status page is reachable.
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          [
            'snapshot',
            { tasks: [{ ...taskCanceled, id: 5 }], queue: { paused: false, taskIds: [] } },
          ],
        ]),
      ),
    );
    await renderTranscript('5');

    expect(await screen.findByText(/canceled while transcribing at/i)).toBeInTheDocument();
    expect(screen.getByText(/19:56/)).toBeInTheDocument(); // transcribedUntilMs 1196480
    // CANCELED carries error: null, so no error message.
    expect(screen.queryByText(/failed to allocate/i)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: /transcript/i })).not.toBeInTheDocument();
  });
});

// jsdom reports colours as rgb(); mirror the helper used by the other tests.
function asRgb(hex: string): string {
  const value = Number.parseInt(hex.slice(1), 16);
  return `rgb(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255})`;
}
