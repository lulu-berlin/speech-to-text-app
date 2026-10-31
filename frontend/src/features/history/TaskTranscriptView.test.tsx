import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { App } from '../../app/App';
import { ConnectionContext } from '../../app/ConnectionContext';
import { SettingsContext, SettingsSync } from '../../app/SettingsContext';
import { lightTheme } from '../../shared/theme/theme';
import { eventStreamResponse } from '../../shared/api/handlers';
import {
  error as errorFixture,
  taskCanceled,
  taskCompleted,
  taskFailed,
  turns as turnsFixture,
} from '../../shared/api/fixtures';
import type { Turns } from '../../shared/api/types';
import { controllableEventStream } from '../../test/eventStream';
import { mediaRulesFor, px, required, styleRules } from '../../test/dom';
import { server } from '../../test/server';
import { ThemedProviders } from '../../test/render';
import { TaskTranscriptView } from './TaskTranscriptView';

function renderTranscript(taskId: string) {
  render(
    <MemoryRouter initialEntries={[`/history/${taskId}`]}>
      <App />
    </MemoryRouter>,
  );
  return screen.findByText('Live');
}

// The current segment's inline editor, opened from its Edit control; returns its field.
function openSegmentEditor(name: string): HTMLElement {
  fireEvent.click(screen.getByRole('button', { name }));
  fireEvent.click(screen.getByRole('button', { name: 'Edit segment' }));
  return screen.getByLabelText('Segment text');
}

// The media element reports its playhead: moves it to `seconds` and fires the time update.
function playheadAt(audio: HTMLAudioElement, seconds: number) {
  audio.currentTime = seconds;
  fireEvent(audio, new Event('timeupdate'));
}

// The current segment played on its own from its Play-segment control, until `seconds`.
function playOnItsOwnUntil(audio: HTMLAudioElement, seconds: number) {
  fireEvent.click(screen.getByRole('button', { name: 'Play segment' }));
  fireEvent(audio, new Event('play'));
  playheadAt(audio, seconds);
}

// turns.json with segment 2's text replaced; `edited` follows whether it differs.
function turnsWithSegmentTwo(text: string): Turns {
  return {
    ...turnsFixture,
    turns: turnsFixture.turns.map((turn) => ({
      ...turn,
      segments: turn.segments.map((segment) =>
        segment.id === 2 ? { ...segment, text, edited: text !== segment.text } : segment,
      ),
    })),
  };
}

// jsdom has no scrollIntoView, which the follow scroll calls; a test that wants to observe
// follow scrolls (or one that just needs the call to not throw) stubs it. The prototype
// assignment and the spy are the same stub, so the call sites share it.
function stubScrollIntoView(): Mock {
  Element.prototype.scrollIntoView = vi.fn();
  return vi.spyOn(Element.prototype as never, 'scrollIntoView') as unknown as Mock;
}

// "Back to playing position" is the stopped state's control: present when following has
// stopped, absent while it holds; clicking it resumes following.
const backButton = () => screen.queryByRole('button', { name: /back to playing position/i });

// A /turns mock that answers body() (by default turns.json) and counts its requests, for the
// tests that check whether an event made the view read /turns again.
function countTurnsRequests(body: () => Turns = () => turnsFixture): () => number {
  let requests = 0;
  server.use(
    http.get('/api/tasks/:id/turns', () => {
      requests += 1;
      return HttpResponse.json(body());
    }),
  );
  return () => requests;
}

// A navigation renders a whole other view; under a loaded test run that can take longer than
// Testing Library's 1 s default (seen with two suites in parallel), so the waits that follow a
// navigation get more time. The assertion is unchanged; a view that never arrives still fails.
const NAVIGATION_TIMEOUT_MS = 5000;

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

    // A turn's timestamp is shown (as a time of day label), in the transcript itself (the
    // player's time label also reads 00:00).
    const region = screen.getByRole('region', { name: 'Transcript' });
    expect(within(region).getByText(/00:00/)).toBeInTheDocument();
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
    expect(
      within(screen.getByRole('region', { name: 'Transcript' })).getByText(/00:00/),
    ).toBeInTheDocument();
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
    // Mock addition: a COMPLETED task with hasAudio false (a task failed/canceled before
    // chunking may still be completed; the stock completed example has audio). The default
    // snapshot's task 7 is QUEUED, which now shows "still in the queue" instead.
    const completedWithoutAudio = { ...taskCompleted, id: 8, hasAudio: false };
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          [
            'snapshot',
            {
              tasks: [completedWithoutAudio],
              queue: { paused: false, taskIds: [] },
            },
          ],
        ]),
      ),
    );
    await renderTranscript('8');

    expect(screen.queryByLabelText(/Audio/)).not.toBeInTheDocument();
    expect(screen.getByText(/has no audio/i)).toBeInTheDocument();
  });

  it('highlights the segment containing the playhead, keeps the one just played through a gap, and moves on seek', async () => {
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const currentSegment = () => document.querySelector('[data-active]');

    // turns.json segments: turn 1 [0,2100) + [2400,5200); turn 2 [5800,9100);
    // turn 3 [9600,14250); gaps at 2100-2400 and 5200-5800.

    // Inside the first segment.
    playheadAt(audio, 1.0); // 1000 ms
    expect(currentSegment()?.textContent).toContain('Good morning.');

    // In the gap after the first turn's first segment: the segment just played stays
    // highlighted until the next one starts (item 52, user decision 2026-10-23; item 9 showed
    // nothing here).
    playheadAt(audio, 2.25); // 2250 ms, between 2100 and 2400
    expect(currentSegment()?.textContent).toContain('Good morning.');

    // The next segment starts at 2400 ms and takes the highlight.
    playheadAt(audio, 2.4);
    expect(currentSegment()?.textContent).toContain('Thanks for joining us today.');

    // The gap between turns 1 and 2 keeps the turn's last segment too.
    playheadAt(audio, 5.5); // 5500 ms, between 5200 and 5800
    expect(currentSegment()?.textContent).toContain('Thanks for joining us today.');

    // Inside the second turn.
    playheadAt(audio, 6.0); // 6000 ms, inside [5800,9100)
    expect(currentSegment()?.textContent).toContain('Happy to be here.');

    // Seeking moves the highlight to the new position.
    audio.currentTime = 3.0; // 3000 ms, inside [2400,5200)
    fireEvent(audio, new Event('seeked'));
    expect(currentSegment()?.textContent).toContain('Thanks for joining us today.');
  });

  it('keeps the last segment highlighted after it ends', async () => {
    // Item 52 (discovered 2026-10-23): the segment just played stays highlighted until the next
    // one starts, and after the last one none does. turns.json's last segment is turn 3's
    // [9600,14250).
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;

    playheadAt(audio, 15.0); // 15000 ms, past 14250
    expect(document.querySelector('[data-active]')?.textContent).toContain(
      "Let's start with your background.",
    );
  });

  it('clicks a segment while not playing to make it current at its start, without playing', async () => {
    // Item 52 (user decision 2026-10-23, one current segment): a click chooses the segment —
    // the player moves to its start, so it is the current segment with the one filled
    // highlight and its Edit control — and a click while not playing does not start playback. There is no
    // separate selection mark. turns.json: segment 2 starts at 2400 ms, segment 4 at 9600 ms.
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    const fourth = screen.getByRole('button', { name: "Let's start with your background." });

    fireEvent.click(second);
    expect(audio.currentTime).toBe(2.4);
    expect(play).not.toHaveBeenCalled();
    expect(second).toHaveAttribute('data-active');
    expect(second).toHaveAttribute('aria-current', 'true');
    expect(getComputedStyle(second).backgroundColor).toBe(asRgb(lightTheme.colors.accent));
    expect(getComputedStyle(second).outlineStyle).not.toBe('solid');
    expect(second.parentElement).toContainElement(
      screen.getByRole('button', { name: 'Edit segment' }),
    );

    // Another click moves the current segment, its highlight and its Edit control; still silent.
    fireEvent.click(fourth);
    expect(audio.currentTime).toBe(9.6);
    expect(play).not.toHaveBeenCalled();
    expect(fourth).toHaveAttribute('data-active');
    expect(fourth).toHaveAttribute('aria-current', 'true');
    expect(second).not.toHaveAttribute('aria-current');
    expect(screen.getAllByRole('button', { name: 'Edit segment' })).toHaveLength(1);
    expect(fourth.parentElement).toContainElement(
      screen.getByRole('button', { name: 'Edit segment' }),
    );
  });

  it('clicks a segment while playing to jump there, and playback goes on', async () => {
    // Item 52 (user decision 2026-10-23): a click while playing makes the segment current at
    // its start and keeps playing from there — no pause, no restart. turns.json: segment 4
    // starts at 9600 ms.
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const player = screen.getByRole('group', { name: 'Audio player' });
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    fireEvent.click(within(player).getByRole('button', { name: 'Play' }));
    fireEvent(audio, new Event('play'));
    const fourth = screen.getByRole('button', { name: "Let's start with your background." });

    fireEvent.click(fourth);
    expect(audio.currentTime).toBe(9.6);
    expect(fourth).toHaveAttribute('aria-current', 'true');
    expect(pause).not.toHaveBeenCalled();
    expect(within(player).getByRole('button', { name: 'Pause' })).toBeInTheDocument();
  });

  it('double-clicks a segment to play from its start, which makes it current', async () => {
    // Item 52 (user decision 2026-10-23): a double-click chooses the segment and plays from its
    // start; the single click only moves the player there (the test above).
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });

    fireEvent.dblClick(second);

    // Sought to the segment's start (2400 ms) and played, so it is the current segment.
    expect(audio.currentTime).toBe(2.4);
    expect(play).toHaveBeenCalledTimes(1);
    expect(second).toHaveAttribute('aria-current', 'true');
    expect(second.parentElement).toContainElement(
      screen.getByRole('button', { name: 'Edit segment' }),
    );
  });

  it('plays from a focused segment on Enter or Space, which makes it current, like the double-click', async () => {
    // Discovered in item 52: the keyboard's way to a segment does what the double-click does,
    // not only the seek. turns.json: segment 2 starts at 2400 ms, segment 4 at 9600 ms.
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    const fourth = screen.getByRole('button', { name: "Let's start with your background." });

    // Enter: the player plays from segment 2's start, so it is current with its Edit control.
    fireEvent.keyDown(second, { key: 'Enter' });
    expect(second).toHaveAttribute('aria-current', 'true');
    expect(second.parentElement).toContainElement(
      screen.getByRole('button', { name: 'Edit segment' }),
    );
    expect(play).toHaveBeenCalledTimes(1);
    expect(audio.currentTime).toBe(2.4);

    // Space on segment 4 does the same: it becomes current and plays from its start.
    fireEvent.keyDown(fourth, { key: ' ' });
    expect(fourth).toHaveAttribute('aria-current', 'true');
    expect(second).not.toHaveAttribute('aria-current');
    expect(fourth.parentElement).toContainElement(
      screen.getByRole('button', { name: 'Edit segment' }),
    );
    expect(play).toHaveBeenCalledTimes(2);
    expect(audio.currentTime).toBe(9.6);
  });

  it('toggles playback with Enter or Space on the current segment: pause, then resume where it paused', async () => {
    // Item 52 (user decision 2026-10-23, the user's keyboard trial): on the current segment the
    // keys pause and resume — they do not restart it — and focus stays on the segment.
    // turns.json: segment 2 is [2400,5200).
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const player = screen.getByRole('group', { name: 'Audio player' });
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    second.focus();

    // Enter on segment 2 (not current yet) plays from its start; playback runs on to 3 s.
    fireEvent.keyDown(second, { key: 'Enter' });
    fireEvent(audio, new Event('play'));
    playheadAt(audio, 3.0);
    expect(play).toHaveBeenCalledTimes(1);

    // Enter on it again, now current and playing: pause where it is; it stays current.
    fireEvent.keyDown(second, { key: 'Enter' });
    expect(pause).toHaveBeenCalledTimes(1);
    expect(play).toHaveBeenCalledTimes(1);
    fireEvent(audio, new Event('pause'));
    expect(audio.currentTime).toBe(3.0);
    expect(second).toHaveAttribute('aria-current', 'true');
    expect(within(player).getByRole('button', { name: 'Play' })).toBeInTheDocument();

    // Space on it while paused: resume from 3 s, not from the segment's start.
    fireEvent.keyDown(second, { key: ' ' });
    expect(play).toHaveBeenCalledTimes(2);
    expect(audio.currentTime).toBe(3.0);
    expect(pause).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(second);
  });

  it('moves focus along with the current segment while playing, unless focus was put elsewhere', async () => {
    // Item 52 (user decision 2026-10-23, the user's keyboard trial): while playing, focus
    // rides along with the current segment, so a repeated Space pauses what is playing; its
    // own move must not scroll (follow mode owns scrolling). Focus the user moved elsewhere
    // stays where it was put. turns.json: segment 2 [2400,5200), segment 3 [5800,9100),
    // segment 4 [9600,14250).
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    const third = screen.getByRole('button', { name: 'Happy to be here.' });
    const fourth = screen.getByRole('button', { name: "Let's start with your background." });

    // Record what each focus() call asks for (jsdom's own focus behavior is kept).
    const focusOptions: (FocusOptions | undefined)[] = [];
    const focus = HTMLElement.prototype.focus;
    vi.spyOn(HTMLElement.prototype, 'focus').mockImplementation(function (
      this: HTMLElement,
      options?: FocusOptions,
    ) {
      focusOptions.push(options);
      focus.call(this, options);
    });

    // Focus is on segment 2, which plays; the playhead runs on into segment 3.
    second.focus();
    fireEvent.keyDown(second, { key: 'Enter' });
    fireEvent(audio, new Event('play'));
    playheadAt(audio, 6.0);
    expect(third).toHaveAttribute('data-active');
    expect(document.activeElement).toBe(third);
    expect(focusOptions.at(-1)).toEqual({ preventScroll: true });

    // Focus put on another segment by hand (a Tab) stays there as the playhead runs on.
    fourth.focus();
    playheadAt(audio, 10.0);
    expect(fourth).toHaveAttribute('data-active');
    expect(document.activeElement).toBe(fourth);
  });

  it('stops on Escape and puts focus on the current segment, which keeps its highlight', async () => {
    // Item 52 (user decision 2026-10-23, the user's keyboard trial; a Stop by the user's
    // Pause/Stop model): Escape stops what is playing and lands on the current segment — not on
    // a segment that only holds the staging focus — and the highlight stays, so Space plays it.
    await renderTranscript('3');
    const region = screen.getByRole('region', { name: 'Transcript' });
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const player = screen.getByRole('group', { name: 'Audio player' });
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    const third = screen.getByRole('button', { name: 'Happy to be here.' });
    const fourth = screen.getByRole('button', { name: "Let's start with your background." });

    // Playing from segment 2; the playhead runs on into segment 3, focus riding along.
    second.focus();
    fireEvent.keyDown(second, { key: 'Enter' });
    fireEvent(audio, new Event('play'));
    playheadAt(audio, 6.0);
    expect(document.activeElement).toBe(third);

    // Escape: stop, keeping segment 3 current and highlighted.
    fireEvent.keyDown(region, { key: 'Escape' });
    expect(pause).toHaveBeenCalledTimes(1);
    expect(third).toHaveAttribute('data-active');
    expect(document.activeElement).toBe(third);
    fireEvent(audio, new Event('pause'));
    expect(within(player).getByRole('button', { name: 'Play' })).toBeInTheDocument();

    // Focus staged on segment 4 while segment 3 is current: Escape lands on segment 3.
    fourth.focus();
    fireEvent.keyDown(fourth, { key: 'Escape' });
    expect(document.activeElement).toBe(third);
    expect(pause).toHaveBeenCalledTimes(1);
    expect(third).toHaveAttribute('aria-current', 'true');
  });

  it('stops on a click that lands on no control, and puts focus on the current segment', async () => {
    // Item 52 (user decision 2026-10-23; a Stop by the user's Pause/Stop model): a click that
    // lands on nothing interactive — not a segment, its controls, nor any button, link or field —
    // removes the staging focus and stops playback, landing on the current segment; the
    // highlight stays.
    await renderTranscript('3');
    const region = screen.getByRole('region', { name: 'Transcript' });
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    const third = screen.getByRole('button', { name: 'Happy to be here.' });
    const fourth = screen.getByRole('button', { name: "Let's start with your background." });

    // Playing from segment 2; the playhead runs on into segment 3, focus riding along.
    second.focus();
    fireEvent.keyDown(second, { key: 'Enter' });
    fireEvent(audio, new Event('play'));
    playheadAt(audio, 6.0);
    expect(document.activeElement).toBe(third);

    // Staging focus moved to segment 4, then a click on the region's own area (no control).
    fourth.focus();
    fireEvent.click(region);
    expect(pause).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(third);
    expect(third).toHaveAttribute('data-active');
    expect(fourth).not.toHaveAttribute('aria-current');
  });

  it('stops ordinary playback on Escape or a click on no control, at the current segment’s start', async () => {
    // The user's Pause/Stop model 2026-10-23: Escape and a click on no control are a Stop —
    // playback ends at the start of the current segment, which stays current, and Space plays it
    // from there. turns.json: segment 3 is [5800,9100).
    await renderTranscript('3');
    const region = screen.getByRole('region', { name: 'Transcript' });
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    const third = screen.getByRole('button', { name: 'Happy to be here.' });

    const stops: [string, () => void][] = [
      ['Escape', () => fireEvent.keyDown(region, { key: 'Escape' })],
      ['a click on no control', () => fireEvent.click(region)],
    ];
    for (const [way, stop] of stops) {
      // Playing from segment 2, run on to 7 s inside segment 3, then stopped.
      pause.mockClear();
      fireEvent.doubleClick(second);
      fireEvent(audio, new Event('play'));
      playheadAt(audio, 7.0);
      stop();
      fireEvent(audio, new Event('pause'));
      expect(pause, way).toHaveBeenCalledTimes(1);
      expect(audio.currentTime, way).toBe(5.8);
      expect(third, way).toHaveAttribute('aria-current', 'true');
      expect(document.activeElement, way).toBe(third);
    }

    // Space then plays segment 3 from its start.
    play.mockClear();
    fireEvent.keyDown(third, { key: ' ' });
    expect(play).toHaveBeenCalledTimes(1);
    expect(audio.currentTime).toBe(5.8);
  });

  it("leaves the player's Play/Pause button and the speakers panel to act as themselves", async () => {
    // Item 52 (user decision 2026-10-23): a click that lands on a control is not "outside" —
    // the player and the speakers panel keep working exactly as themselves.
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const player = screen.getByRole('group', { name: 'Audio player' });
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);

    // Play: plays, and the outside rule adds no stop of its own.
    fireEvent.click(within(player).getByRole('button', { name: 'Play' }));
    expect(play).toHaveBeenCalledTimes(1);
    expect(pause).not.toHaveBeenCalled();

    // Pause while playing: pauses exactly once — the outside rule does not also stop.
    fireEvent(audio, new Event('play'));
    fireEvent.click(within(player).getByRole('button', { name: 'Pause' }));
    expect(pause).toHaveBeenCalledTimes(1);

    // The speakers panel's name field is a control too: a click into it does not stop playback.
    fireEvent.click(screen.getByLabelText('Name of speaker 1'));
    expect(pause).toHaveBeenCalledTimes(1);
  });

  it("hands focus to the current segment after the player's Play/Pause is pressed", async () => {
    // Item 52 (user decision 2026-10-23, the user's keyboard trial): the player's button does
    // not keep focus; it goes to the current segment — so Enter/Space toggles there, and Tab
    // continues from it. At 0 ms turns.json's segment 1 [0,2100) is current.
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const player = screen.getByRole('group', { name: 'Audio player' });
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const first = screen.getByRole('button', { name: 'Good morning.' });

    fireEvent.click(within(player).getByRole('button', { name: 'Play' }));
    expect(play).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(first);

    // With focus there, the keys toggle what is playing.
    fireEvent(audio, new Event('play'));
    fireEvent.keyDown(document.activeElement!, { key: ' ' });
    expect(pause).toHaveBeenCalledTimes(1);
  });

  it("shows the current segment's Edit control only while nothing plays", async () => {
    // Item 52 (user decision 2026-10-23): while playing, no control shows, so nothing moves
    // under the pointer; while nothing plays, the control sits right after the current segment.
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const player = screen.getByRole('group', { name: 'Audio player' });
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const first = screen.getByRole('button', { name: 'Good morning.' });
    const third = screen.getByRole('button', { name: 'Happy to be here.' });
    const edit = () => screen.queryByRole('button', { name: 'Edit segment' });

    // Not playing, at 0 ms: segment 1 is current and carries the control.
    expect(first.parentElement).toContainElement(edit());

    // Playing: none anywhere, nor as the playhead runs on.
    fireEvent.click(within(player).getByRole('button', { name: 'Play' }));
    fireEvent(audio, new Event('play'));
    expect(edit()).not.toBeInTheDocument();
    playheadAt(audio, 6.0);
    expect(edit()).not.toBeInTheDocument();

    // Paused again: the control returns, after the current segment (segment 3).
    fireEvent.click(within(player).getByRole('button', { name: 'Pause' }));
    fireEvent(audio, new Event('pause'));
    expect(third).toHaveAttribute('data-active');
    expect(third.parentElement).toContainElement(edit());
  });

  it('moves the staging focus between segments with the arrow keys, without playing', async () => {
    // Item 52 (user decision 2026-10-23): with focus on a segment the arrows step to the
    // previous (Left/Up) or next (Right/Down) segment, skipping the current one's control;
    // creating the staging focus is a scroll away (the user's trial), so following stops.
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const first = screen.getByRole('button', { name: 'Good morning.' });
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    const third = screen.getByRole('button', { name: 'Happy to be here.' });
    first.focus();
    // The current segment (segment 1, at 0 ms, not playing) has its control right after it.
    expect(screen.getByRole('button', { name: 'Edit segment' })).toBeInTheDocument();

    fireEvent.keyDown(first, { key: 'ArrowRight' });
    expect(document.activeElement).toBe(second);
    fireEvent.keyDown(second, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(third);
    fireEvent.keyDown(third, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(second);
    fireEvent.keyDown(second, { key: 'ArrowLeft' });
    expect(document.activeElement).toBe(first);

    // Nothing played or seeked, and the first step (focus onto a segment that is not current)
    // counted as a scroll away, so following stopped.
    expect(audio.currentTime).toBe(0);
    expect(backButton()).toBeInTheDocument();
  });

  it('stops the autoscroll when a staging focus is created', async () => {
    // Discovered in the user's trial (2026-10-23): focus put on a segment that is not current
    // — Tab, or an arrow key — is a scroll away by hand, so the follow autoscroll stops.
    const scrollIntoView = stubScrollIntoView();
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const fourth = screen.getByRole('button', { name: "Let's start with your background." });

    // Following scrolled the current segment into view at load, and again at segment 3.
    playheadAt(audio, 6.0);
    expect(scrollIntoView).toHaveBeenCalledTimes(2);

    // Staging focus on segment 4 (segment 3 is current): following stops. A raw focus() needs
    // act(), as React re-renders from the focus event it causes.
    act(() => fourth.focus());
    expect(backButton()).toBeInTheDocument();

    // The playhead runs into segment 4: nothing scrolls for it any more.
    playheadAt(audio, 10.0);
    expect(scrollIntoView).toHaveBeenCalledTimes(2);
  });

  it('makes a segment that shares its start with the next current when it is clicked', async () => {
    // User report 2026-10-23 (a song's refrain): whisper can emit a segment at the same start as
    // the next one, and the last-starting rule then skipped it — it could be focused but never
    // chosen. The chosen segment wins at its own instant, and the playhead leaving its start
    // hands over to the next.
    const sharedStart = {
      ...turnsFixture,
      turns: turnsFixture.turns.map((turn) =>
        turn.startMs === 9600
          ? {
              ...turn,
              segments: [
                // The duplicate: zero length, at the next segment's start.
                { id: 5, startMs: 9600, endMs: 9600, text: 'Ghosted by the bar' },
                ...turn.segments,
              ],
            }
          : turn,
      ),
    };
    server.use(http.get('/api/tasks/:id/turns', () => HttpResponse.json(sharedStart)));
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const duplicate = screen.getByRole('button', { name: 'Ghosted by the bar' });
    const next = screen.getByRole('button', { name: "Let's start with your background." });

    fireEvent.click(duplicate);
    expect(audio.currentTime).toBe(9.6);
    expect(duplicate).toHaveAttribute('data-active');
    expect(next).not.toHaveAttribute('data-active');
    expect(duplicate.parentElement).toContainElement(
      screen.getByRole('button', { name: 'Edit segment' }),
    );

    // Playing it, the next segment takes over as soon as the playhead leaves 9.6 s.
    fireEvent.keyDown(duplicate, { key: 'Enter' });
    fireEvent(audio, new Event('play'));
    playheadAt(audio, 9.7);
    expect(next).toHaveAttribute('data-active');
    expect(duplicate).not.toHaveAttribute('data-active');
  });

  it('keeps a clicked segment current when the browser confirms the seek to its start', async () => {
    // CI 2026-10-24: a click on the segment starting at 32760 ms seeks the audio to 32.76 s, and
    // the browser's `seeked` then reports 32.76 s — 32759.999999999996 ms once multiplied, before
    // the segment's start — so the previous segment became current and the Edit and Play
    // controls moved to it.
    const atAnInexactStart: Turns = {
      ...turnsFixture,
      turns: [
        {
          speakerId: 0,
          startMs: 24600,
          endMs: 40080,
          text: 'Ask not what your country can do for you. Ask what you can do.',
          segments: [
            {
              id: 1,
              startMs: 24600,
              endMs: 32760,
              text: 'Ask not what your country can do for you.',
              edited: false,
            },
            { id: 2, startMs: 32760, endMs: 40080, text: 'Ask what you can do.', edited: false },
          ],
        },
      ],
    };
    server.use(http.get('/api/tasks/:id/turns', () => HttpResponse.json(atAnInexactStart)));
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const clicked = await screen.findByRole('button', { name: 'Ask what you can do.' });
    const previous = screen.getByRole('button', {
      name: 'Ask not what your country can do for you.',
    });

    fireEvent.click(clicked);
    fireEvent(audio, new Event('seeked'));

    expect(clicked).toHaveAttribute('data-active');
    expect(previous).not.toHaveAttribute('data-active');
    expect(clicked.parentElement).toContainElement(
      screen.getByRole('button', { name: 'Edit segment' }),
    );
  });

  it('shows a segment with no text as a grayed-out [empty] that can be chosen', async () => {
    // User decision 2026-10-23 (a song whisper could not render): a text-less segment gets a
    // grayed-out placeholder in the view so it is not a hole in the text; it stays clickable and
    // choosable, so a missed phrase can be typed in later, and the placeholder never reaches the
    // downloads — those render the stored text.
    const blankTurn = {
      ...turnsFixture,
      turns: turnsFixture.turns.map((turn) =>
        turn.startMs === 5800
          ? { ...turn, segments: turn.segments.map((segment) => ({ ...segment, text: '   ' })) }
          : turn,
      ),
    };
    server.use(http.get('/api/tasks/:id/turns', () => HttpResponse.json(blankTurn)));
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const empty = screen.getByRole('button', { name: '[empty]' });
    expect(getComputedStyle(empty).color).toBe(asRgb(lightTheme.colors.textMuted));

    fireEvent.click(empty);
    expect(audio.currentTime).toBe(5.8);
    expect(empty).toHaveAttribute('data-active');
    expect(empty.parentElement).toContainElement(
      screen.getByRole('button', { name: 'Edit segment' }),
    );
    const txt = screen.getByRole('link', { name: 'Download transcript (txt)' });
    expect(txt).toHaveAttribute('href', '/api/tasks/3/transcript?format=txt');
  });

  it("opens a segment's text in an inline editor and shows the saved edit in place", async () => {
    // Item 52: the current segment's Edit control opens a textarea in the segment's place,
    // pre-filled with its stored text; Save sends it and the view shows the answer's turns —
    // no /turns refetch (the default handler would answer the old text).
    const editedTurns = turnsWithSegmentTwo('Thanks, everyone.');
    const sent: unknown[] = [];
    server.use(
      http.put('/api/tasks/:id/segments/:segmentId', async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json(editedTurns);
      }),
    );
    await renderTranscript('3');
    fireEvent.click(screen.getByRole('button', { name: 'Thanks for joining us today.' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit segment' }));

    const editor = screen.getByLabelText('Segment text');
    expect(editor).toHaveValue('Thanks for joining us today.');
    fireEvent.change(editor, { target: { value: 'Thanks, everyone.' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(sent).toEqual([{ text: 'Thanks, everyone.' }]));
    expect(await screen.findByRole('button', { name: 'Thanks, everyone.' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Segment text')).not.toBeInTheDocument();
  });

  it('takes no newlines in the editor: Enter saves, Shift+Enter does nothing, a pasted break is a space', async () => {
    // User decision 2026-10-23 after trying the editor: one line only. Enter and Ctrl+Enter
    // save, Shift+Enter does nothing, and a line break in typed or pasted text becomes a space.
    const editedTurns = turnsWithSegmentTwo('Thanks, everyone.');
    const sent: unknown[] = [];
    server.use(
      http.put('/api/tasks/:id/segments/:segmentId', async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json(editedTurns);
      }),
    );
    await renderTranscript('3');

    const editor = openSegmentEditor('Thanks for joining us today.');

    // Shift+Enter: no save, no change, still open.
    fireEvent.keyDown(editor, { key: 'Enter', shiftKey: true });
    expect(screen.getByLabelText('Segment text')).toHaveValue('Thanks for joining us today.');
    expect(sent).toEqual([]);

    // A pasted line break becomes a space.
    fireEvent.change(editor, { target: { value: 'Thanks,\neveryone.' } });
    expect(editor).toHaveValue('Thanks, everyone.');

    // Enter saves and closes.
    fireEvent.keyDown(editor, { key: 'Enter' });
    await waitFor(() => expect(sent).toEqual([{ text: 'Thanks, everyone.' }]));
    expect(screen.queryByLabelText('Segment text')).not.toBeInTheDocument();

    // Ctrl+Enter saves too (the view now shows the answer's text).
    const again = openSegmentEditor('Thanks, everyone.');
    fireEvent.change(again, { target: { value: 'Thanks again.' } });
    fireEvent.keyDown(again, { key: 'Enter', ctrlKey: true });
    await waitFor(() => expect(sent).toHaveLength(2));
  });

  it('closes the editor on Discard or Escape without saving, and a click outside leaves it open', async () => {
    // User decisions 2026-10-23: Discard and Escape abandon the draft (nothing stored, no
    // request); any other click — even one that lands on no control — leaves the editor and its
    // draft alone, so typing is not lost by a stray click.
    const sent: unknown[] = [];
    server.use(
      http.put('/api/tasks/:id/segments/:segmentId', async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json(turnsFixture);
      }),
    );
    await renderTranscript('3');
    const region = screen.getByRole('region', { name: 'Transcript' });
    const stored = 'Thanks for joining us today.';

    // Discard: the stored text stays, the editor closes, no request.
    const editor = openSegmentEditor(stored);
    fireEvent.change(editor, { target: { value: 'Half-typed thought' } });
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }));
    expect(screen.queryByLabelText('Segment text')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: stored })).toBeInTheDocument();
    expect(sent).toEqual([]);

    // A click on the region's own area keeps the editor and its draft open.
    const again = openSegmentEditor(stored);
    fireEvent.change(again, { target: { value: 'Still typing' } });
    fireEvent.click(region);
    expect(screen.getByLabelText('Segment text')).toHaveValue('Still typing');

    // Escape abandons the draft from inside the field.
    fireEvent.keyDown(again, { key: 'Escape' });
    expect(screen.queryByLabelText('Segment text')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: stored })).toBeInTheDocument();
    expect(sent).toEqual([]);
  });

  it("puts the player's Play/Pause under the editor's dialog, disabled", async () => {
    // User decision 2026-10-23: playback cannot start under an unfinished edit. The dialog's
    // modal page puts the player out of reach; its button also stays disabled (item 52) for any
    // path that ignores the shade.
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const player = screen.getByRole('group', { name: 'Audio player' });
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    fireEvent.click(screen.getByRole('button', { name: 'Thanks for joining us today.' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit segment' }));

    expect(within(player).queryByRole('button', { name: 'Play' })).not.toBeInTheDocument();
    const button = required(player.querySelector('button'));
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(play).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Discard' }));
    expect(within(player).getByRole('button', { name: 'Play' })).toBeEnabled();
  });

  it("names the edited segment in the dialog's title: its speaker and time range", async () => {
    // The user's review 2026-10-23: the dialog says which segment it edits.
    await renderTranscript('3');
    openSegmentEditor('Thanks for joining us today.');
    // turns.json: segment 2 (Speaker 1) is [2400,5200) — "00:02–00:05".
    expect(screen.getByRole('dialog')).toHaveAccessibleName('Speaker 1 · 00:02–00:05');

    // Speakers off (task 4, turns.speakers-off.json): the range alone; segment 2 is [4100,7900).
    cleanup();
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
    openSegmentEditor('And call the dentist on Monday.');
    expect(screen.getByRole('dialog')).toHaveAccessibleName('00:04–00:07');
  });

  it("returns focus to the segment's Edit control when the editor closes, the segment still current", async () => {
    // The user's review 2026-10-23: closing the editor — Save, Discard, Escape (and Revert, the
    // same path) — puts focus back where it was opened from, on the segment being edited.
    await renderTranscript('3');
    const ways: [string, () => void][] = [
      ['Save', () => fireEvent.click(screen.getByRole('button', { name: 'Save' }))],
      ['Discard', () => fireEvent.click(screen.getByRole('button', { name: 'Discard' }))],
      ['Escape', () => fireEvent.keyDown(screen.getByLabelText('Segment text'), { key: 'Escape' })],
    ];
    for (const [way, close] of ways) {
      openSegmentEditor('Thanks for joining us today.');
      close();
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument(), {
        timeout: 2000,
      });
      expect(document.activeElement, way).toBe(
        screen.getByRole('button', { name: 'Edit segment' }),
      );
      expect(
        screen.getByRole('button', { name: 'Thanks for joining us today.' }),
        way,
      ).toHaveAttribute('aria-current', 'true');
    }
  });

  it('opens the editor with e, plays the segment with p, and saves or discards with Alt or Ctrl and the letter', async () => {
    // Root review 2026-10-23, the user's shortcuts: e and p act on the focused segment; in the
    // dialog Alt+S / Alt+D (and Ctrl+S / Ctrl+D, the forms the user also expects) save and
    // discard, and the buttons underline their letter. turns.json: segment 2 is [2400,5200).
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    second.focus();

    // e opens the editor; the controls advertise their keys.
    expect(screen.getByRole('button', { name: 'Edit segment' })).toHaveAttribute(
      'aria-keyshortcuts',
      'E',
    );
    expect(screen.getByRole('button', { name: 'Play segment' })).toHaveAttribute(
      'aria-keyshortcuts',
      'P',
    );
    fireEvent.keyDown(second, { key: 'e' });
    const dialog = screen.getByRole('dialog');
    expect(dialog).toBeInTheDocument();
    const save = within(dialog).getByRole('button', { name: 'Save' });
    const discard = within(dialog).getByRole('button', { name: 'Discard' });
    expect(save.querySelector('u')?.textContent).toBe('S');
    expect(discard.querySelector('u')?.textContent).toBe('D');

    // Ctrl+S saves an unchanged draft (it just closes); Alt+D discards.
    fireEvent.keyDown(screen.getByLabelText('Segment text'), { key: 's', ctrlKey: true });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    second.focus();
    fireEvent.keyDown(second, { key: 'e' });
    fireEvent.keyDown(screen.getByLabelText('Segment text'), { key: 'd', altKey: true });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    // p plays the segment on its own; p again stops it.
    second.focus();
    fireEvent.keyDown(second, { key: 'p' });
    expect(play).toHaveBeenCalledTimes(1);
    expect(audio.currentTime).toBe(2.4);
    fireEvent(audio, new Event('play'));
    fireEvent.keyDown(second, { key: 'p' });
    fireEvent(audio, new Event('pause'));
    expect(pause).toHaveBeenCalledTimes(1);
    expect(audio.currentTime).toBe(2.4);
  });

  it('keeps following while the user types in the open editor, so playback still scrolls after it closes', async () => {
    // Root review 2026-10-23: the dialog's field events still bubble through the React tree into
    // the transcript region, whose scroll keys treat Space and the arrows as a hand scroll —
    // following stopped although the user never scrolled, and only showed once the dialog closed.
    const scrollIntoView = stubScrollIntoView();
    await renderTranscript('3');
    const field = openSegmentEditor('Thanks for joining us today.');

    // A space typed and the caret moved with the arrows: neither is a hand scroll.
    fireEvent.keyDown(field, { key: ' ' });
    fireEvent.keyDown(field, { key: 'ArrowLeft' });
    fireEvent.keyDown(field, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(
      screen.queryByRole('button', { name: 'Back to playing position' }),
    ).not.toBeInTheDocument();

    // Playback still follows: choosing another segment scrolls it into view.
    scrollIntoView.mockClear();
    fireEvent.click(screen.getByRole('button', { name: "Let's start with your background." }));
    expect(scrollIntoView).toHaveBeenCalled();
  });

  it('operates its segment controls with Enter and Space, Space there not scrolling or stopping follow', async () => {
    // Root review 2026-10-23: the controls are button-role spans, so they need Enter and Space
    // themselves; Space on them must not fall into the region's scroll keys, which would stop
    // follow mode. turns.json: segment 2 is [2400,5200).
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    fireEvent.click(second);

    // Enter on Edit opens the editor.
    const edit = screen.getByRole('button', { name: 'Edit segment' });
    edit.focus();
    fireEvent.keyDown(edit, { key: 'Enter' });
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    fireEvent.keyDown(screen.getByLabelText('Segment text'), { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    // Space on Play-segment plays it on its own — and is not a scroll key: following stays on.
    const preview = screen.getByRole('button', { name: 'Play segment' });
    preview.focus();
    fireEvent.keyDown(preview, { key: ' ' });
    expect(play).toHaveBeenCalledTimes(1);
    expect(audio.currentTime).toBe(2.4);
    expect(
      screen.queryByRole('button', { name: 'Back to playing position' }),
    ).not.toBeInTheDocument();

    // Enter on Stop (the same control while it plays) stops it back at the segment's start.
    fireEvent(audio, new Event('play'));
    const stop = screen.getByRole('button', { name: 'Stop segment' });
    stop.focus();
    fireEvent.keyDown(stop, { key: 'Enter' });
    fireEvent(audio, new Event('pause'));
    expect(pause).toHaveBeenCalledTimes(1);
    expect(audio.currentTime).toBe(2.4);
  });

  it('opens the editor as a modal dialog, the page inert and focus kept inside', async () => {
    // The user's review 2026-10-23: the editor is a modal dialog — the page under the shade does
    // not answer, does not scroll, and focus stays inside until it closes. Radix's dialog does
    // the trapping (as ConfirmDialog does); jsdom sees the structure and the focus cycle, the
    // browser behavior is the e2e test's.
    await renderTranscript('3');
    const region = screen.getByRole('region', { name: 'Transcript' });
    const field = openSegmentEditor('Thanks for joining us today.');

    const dialog = screen.getByRole('dialog');
    expect(dialog).toContainElement(field);
    // Portalled out of the page, which the modal keeps out of reach.
    expect(region).not.toContainElement(dialog);
    expect(dialog).toContainElement(document.activeElement as HTMLElement);

    // Tab wraps: from the last control to the field, and Shift+Tab from the field to the last.
    const last = within(dialog).getByRole('button', { name: 'Discard' });
    last.focus();
    fireEvent.keyDown(last, { key: 'Tab' });
    expect(document.activeElement).toBe(field);
    fireEvent.keyDown(field, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(last);

    // Escape closes it: the draft is gone and the page answers again.
    fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(document.body.style.overflow).toBe('');
  });

  it("offers Revert only for an edited segment and brings whisper's text back in place", async () => {
    // ADR 0061: the turns say whether a segment differs from whisper's line; Revert posts to the
    // revert route and the view applies the answer in place, no /turns refetch.
    const editedTurns = turnsWithSegmentTwo('Corrected sentence.');
    const reverted: string[] = [];
    server.use(
      http.get('/api/tasks/:id/turns', () => HttpResponse.json(editedTurns)),
      http.post('/api/tasks/:id/segments/:segmentId/revert', ({ params }) => {
        reverted.push(String(params.segmentId));
        return HttpResponse.json(turnsFixture);
      }),
    );
    await renderTranscript('3');

    // An unedited segment's editor offers no Revert.
    fireEvent.click(screen.getByRole('button', { name: 'Happy to be here.' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit segment' }));
    expect(screen.queryByRole('button', { name: 'Revert' })).not.toBeInTheDocument();
    fireEvent.keyDown(screen.getByLabelText('Segment text'), { key: 'Escape' });

    // The edited one does: the answer's text replaces it in place and the editor closes.
    fireEvent.click(screen.getByRole('button', { name: 'Corrected sentence.' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit segment' }));
    fireEvent.click(screen.getByRole('button', { name: 'Revert' }));
    await waitFor(() => expect(reverted).toEqual(['2']));
    expect(
      await screen.findByRole('button', { name: 'Thanks for joining us today.' }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Segment text')).not.toBeInTheDocument();
  });

  it('closes without a request when the save would store the text the editor opened with', async () => {
    // User rule 2026-10-23: an unchanged draft is not a save — Save, Enter and Ctrl+Enter close
    // the editor and send nothing; whitespace-only changes count as unchanged (both trimmed).
    const sent: unknown[] = [];
    server.use(
      http.put('/api/tasks/:id/segments/:segmentId', async ({ request }) => {
        sent.push(await request.json());
        return HttpResponse.json(turnsFixture);
      }),
    );
    await renderTranscript('3');

    // Save, untouched: closes, nothing sent.
    openSegmentEditor('Thanks for joining us today.');
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByLabelText('Segment text')).not.toBeInTheDocument());
    expect(sent).toEqual([]);

    // Whitespace around the same text is the same save: Enter closes, nothing sent.
    const editor = openSegmentEditor('Thanks for joining us today.');
    fireEvent.change(editor, { target: { value: '  Thanks for joining us today.  ' } });
    fireEvent.keyDown(editor, { key: 'Enter' });
    expect(screen.queryByLabelText('Segment text')).not.toBeInTheDocument();
    expect(sent).toEqual([]);
  });

  it('keeps the draft open and shows the error when the save is rejected', async () => {
    // Item 52: a rejected edit stores nothing; the editor stays with its draft and the server's
    // detail, so the text can be fixed and saved again.
    server.use(
      http.put('/api/tasks/:id/segments/:segmentId', () =>
        HttpResponse.json(
          {
            error: 'invalid-segment-text',
            message:
              'segment text must not be empty, whitespace-only or contain control characters',
          },
          { status: 400 },
        ),
      ),
    );
    await renderTranscript('3');
    fireEvent.click(screen.getByRole('button', { name: 'Thanks for joining us today.' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit segment' }));
    const editor = screen.getByLabelText('Segment text');
    fireEvent.change(editor, { target: { value: 'A rejected line' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('segment text must not be empty');
    expect(screen.getByLabelText('Segment text')).toHaveValue('A rejected line');

    // Closing without saving keeps the stored text.
    fireEvent.click(screen.getByRole('button', { name: 'Discard' }));
    expect(
      screen.getByRole('button', { name: 'Thanks for joining us today.' }),
    ).toBeInTheDocument();
  });

  it("plays exactly one segment window from the current segment's Play-segment control", async () => {
    // Item 52: isolated playback — from the segment's start, stopping at its end, not playing on
    // into the next segment. turns.json: segment 2 is [2400,5200).
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    fireEvent.click(screen.getByRole('button', { name: 'Thanks for joining us today.' }));

    fireEvent.click(screen.getByRole('button', { name: 'Play segment' }));
    expect(audio.currentTime).toBe(2.4);
    expect(play).toHaveBeenCalledTimes(1);

    // Inside the window nothing stops it.
    playheadAt(audio, 5.0);
    expect(pause).not.toHaveBeenCalled();

    // At the segment's end it stops by itself.
    playheadAt(audio, 5.2);
    expect(pause).toHaveBeenCalledTimes(1);
  });

  it('stops a segment played on its own at its end back at its start, the segment still current', async () => {
    // The user's review 2026-10-23: a segment played on its own is stopped, not paused, at its
    // end — the playhead goes back to its start and the next segment never becomes current.
    // turns.json: segment 2 is [2400,5200), segment 3 starts at 5800. timeupdate fires
    // coarsely, so the first one past the end can already lie inside segment 3.
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    const third = screen.getByRole('button', { name: 'Happy to be here.' });
    fireEvent.click(second);
    fireEvent.click(screen.getByRole('button', { name: 'Play segment' }));
    fireEvent(audio, new Event('play'));

    playheadAt(audio, 5.9);
    fireEvent(audio, new Event('pause'));

    expect(pause).toHaveBeenCalledTimes(1);
    expect(audio.currentTime).toBe(2.4);
    expect(second).toHaveAttribute('aria-current', 'true');
    expect(third).not.toHaveAttribute('aria-current');
    expect(second.parentElement).toContainElement(
      screen.getByRole('button', { name: 'Play segment' }),
    );
  });

  it('pauses a segment played on its own in place on Space, Enter or the player, and still stops it at its end', async () => {
    // The user's Pause/Stop model 2026-10-23: a Pause holds the playhead where it is, and resuming
    // finishes the segment on its own, stopping at its end back at its start.
    // turns.json: segment 2 is [2400,5200).
    await renderTranscript('3');
    const player = screen.getByRole('group', { name: 'Audio player' });
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    fireEvent.click(second);

    const pauses: [string, () => void][] = [
      ['Space on the segment', () => fireEvent.keyDown(second, { key: ' ' })],
      ['Enter on the segment', () => fireEvent.keyDown(second, { key: 'Enter' })],
      ["the player's button", () => fireEvent.click(within(player).getByRole('button'))],
    ];
    for (const [way, toggle] of pauses) {
      pause.mockClear();
      playOnItsOwnUntil(audio, 3.6);
      toggle();
      fireEvent(audio, new Event('pause'));
      expect(pause, way).toHaveBeenCalledTimes(1);
      expect(audio.currentTime, way).toBe(3.6);
      expect(second, way).toHaveAttribute('aria-current', 'true');

      // Resumed in place, it still stops at the segment's end, back at its start.
      toggle();
      fireEvent(audio, new Event('play'));
      expect(audio.currentTime, way).toBe(3.6);
      playheadAt(audio, 5.3);
      fireEvent(audio, new Event('pause'));
      expect(pause, way).toHaveBeenCalledTimes(2);
      expect(audio.currentTime, way).toBe(2.4);
      expect(second, way).toHaveAttribute('aria-current', 'true');
    }
  });

  it('stops a segment played on its own on Escape or a click on no control, back at its start', async () => {
    // The user's Pause/Stop model 2026-10-23: a Stop ends playback at the segment's start, the
    // segment still current, so Space then plays it from its beginning — the way a click on a
    // segment seeks to its start. turns.json: segment 2 is [2400,5200).
    await renderTranscript('3');
    const region = screen.getByRole('region', { name: 'Transcript' });
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    fireEvent.click(second);

    const stops: [string, () => void][] = [
      ['Escape', () => fireEvent.keyDown(region, { key: 'Escape' })],
      ['a click on no control', () => fireEvent.click(region)],
    ];
    for (const [way, stop] of stops) {
      pause.mockClear();
      playOnItsOwnUntil(audio, 3.6);
      stop();
      fireEvent(audio, new Event('pause'));
      expect(pause, way).toHaveBeenCalledTimes(1);
      expect(audio.currentTime, way).toBe(2.4);
      expect(second, way).toHaveAttribute('aria-current', 'true');
    }

    // Space then plays the segment from its beginning.
    play.mockClear();
    fireEvent.keyDown(second, { key: ' ' });
    expect(play).toHaveBeenCalledTimes(1);
    expect(audio.currentTime).toBe(2.4);
  });

  it('ends playing a segment on its own when another segment is chosen or the player seeks', async () => {
    // Confirmed 2026-10-23: choosing another segment or seeking ends a segment played on its own;
    // playback goes on as an ordinary play, so the old segment's end (5200 ms) no longer stops
    // it. turns.json: segment 2 is [2400,5200), segment 4 starts at 9600; one second back from
    // 3 s on the seek bar is 2 s, inside segment 1.
    await renderTranscript('3');
    const player = screen.getByRole('group', { name: 'Audio player' });
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    const fourth = screen.getByRole('button', { name: "Let's start with your background." });

    const ways: [string, () => void, number][] = [
      ['a click on segment 4', () => fireEvent.click(fourth), 10.0],
      ['a double-click on segment 4', () => fireEvent.doubleClick(fourth), 10.0],
      ['Enter on segment 4', () => fireEvent.keyDown(fourth, { key: 'Enter' }), 10.0],
      [
        'the seek bar',
        () =>
          fireEvent.keyDown(within(player).getByRole('slider', { name: 'Seek' }), {
            key: 'ArrowLeft',
          }),
        5.3,
      ],
    ];
    for (const [way, leave, laterSeconds] of ways) {
      // Segment 2 played on its own, at 3 s.
      fireEvent(audio, new Event('pause'));
      fireEvent.click(second);
      pause.mockClear();
      playOnItsOwnUntil(audio, 3.0);

      leave();
      playheadAt(audio, laterSeconds);
      expect(pause, way).not.toHaveBeenCalled();
      expect(audio.currentTime, way).toBe(laterSeconds);
    }
  });

  it("turns the editor's preview into a Stop while the segment plays, keeping focus and the caret in the field", async () => {
    // The user's review 2026-10-23: Play in the dialog plays the segment on its own; while it
    // plays the same control is a Stop, and Stop — or the segment's end — stops it back at the
    // segment's start. Focus never leaves the field, so the user keeps typing. turns.json:
    // segment 2 is [2400,5200).
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const field = openSegmentEditor('Thanks for joining us today.') as HTMLTextAreaElement;
    fireEvent.change(field, { target: { value: 'Typing while listening' } });
    field.setSelectionRange(5, 5);
    const dialog = screen.getByRole('dialog');

    fireEvent.click(within(dialog).getByRole('button', { name: 'Play segment' }));
    fireEvent(audio, new Event('play'));
    expect(play).toHaveBeenCalledTimes(1);
    expect(audio.currentTime).toBe(2.4);

    const stop = within(dialog).getByRole('button', { name: 'Stop segment' });
    expect(stop.querySelector('svg.lucide-square')).not.toBeNull();
    expect(screen.getByLabelText('Segment text')).toHaveValue('Typing while listening');
    expect(document.activeElement).toBe(field);
    expect(field.selectionStart).toBe(5);

    // Stop: back at the segment's start, the dialog and the draft still there, Play again.
    fireEvent.click(stop);
    fireEvent(audio, new Event('pause'));
    expect(pause).toHaveBeenCalledTimes(1);
    expect(audio.currentTime).toBe(2.4);
    expect(document.activeElement).toBe(field);
    expect(field.selectionStart).toBe(5);
    expect(within(dialog).getByRole('button', { name: 'Play segment' })).toBeInTheDocument();
    expect(screen.getByLabelText('Segment text')).toHaveValue('Typing while listening');

    // The segment's end is a Stop too, with the dialog and the draft open throughout.
    fireEvent.click(within(dialog).getByRole('button', { name: 'Play segment' }));
    fireEvent(audio, new Event('play'));
    playheadAt(audio, 5.3);
    fireEvent(audio, new Event('pause'));
    expect(audio.currentTime).toBe(2.4);
    expect(screen.getByLabelText('Segment text')).toHaveValue('Typing while listening');
  });

  it('shows a square Stop and a disabled Edit while a segment plays on its own', async () => {
    // The user's review 2026-10-23, choice (a): while a segment plays on its own its Play-segment
    // control is a Stop (a square), and Edit stays beside it disabled — grayed out, not clickable
    // — so the Stop keeps the Play control's place. Stop is a Stop: back at the segment's start.
    // turns.json: segment 2 is [2400,5200).
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const second = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    fireEvent.click(second);
    playOnItsOwnUntil(audio, 3.6);

    const stop = screen.getByRole('button', { name: 'Stop segment' });
    expect(second.parentElement).toContainElement(stop);
    expect(stop.querySelector('svg.lucide-square')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'Play segment' })).not.toBeInTheDocument();
    const edit = screen.getByRole('button', { name: 'Edit segment' });
    expect(edit).toHaveAttribute('aria-disabled', 'true');
    expect(getComputedStyle(edit).color).toBe(asRgb(lightTheme.colors.textMuted));
    fireEvent.click(edit);
    expect(screen.queryByLabelText('Segment text')).not.toBeInTheDocument();

    fireEvent.click(stop);
    fireEvent(audio, new Event('pause'));
    expect(pause).toHaveBeenCalledTimes(1);
    expect(audio.currentTime).toBe(2.4);
    expect(second).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('button', { name: 'Play segment' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit segment' })).not.toHaveAttribute(
      'aria-disabled',
    );
  });

  it('keeps the editor and its draft open while its own preview plays the segment', async () => {
    // User decision 2026-10-23: replay a segment to check a word without leaving the editor —
    // the preview lives in the editor's box, and the draft survives it.
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    fireEvent.click(screen.getByRole('button', { name: 'Thanks for joining us today.' }));
    fireEvent.click(screen.getByRole('button', { name: 'Edit segment' }));
    const editor = screen.getByLabelText('Segment text');
    fireEvent.change(editor, { target: { value: 'Typing while listening' } });

    fireEvent.click(screen.getByRole('button', { name: 'Play segment' }));
    expect(audio.currentTime).toBe(2.4);
    expect(play).toHaveBeenCalledTimes(1);
    expect(screen.getByLabelText('Segment text')).toHaveValue('Typing while listening');
  });

  it('skips the silence before the first segment, on playback and on seeking', async () => {
    // Discovered 2026-10-23: silence before the first segment is unplayable — starting or
    // seeking into it puts the playhead at the first segment's start, so a current segment
    // always exists. This transcript's first segment starts at 800 ms.
    const lateStart = {
      ...turnsFixture,
      turns: [
        {
          ...turnsFixture.turns[0],
          startMs: 800,
          segments: [
            { id: 1, startMs: 800, endMs: 2100, text: 'Good morning.', edited: false },
            {
              id: 2,
              startMs: 2400,
              endMs: 5200,
              text: 'Thanks for joining us today.',
              edited: false,
            },
          ],
        },
        turnsFixture.turns[1],
        turnsFixture.turns[2],
      ],
    };
    server.use(http.get('/api/tasks/:id/turns', () => HttpResponse.json(lateStart)));
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const player = screen.getByRole('group', { name: 'Audio player' });
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());

    // Play pressed in the silence: the player starts at the first segment, which is current.
    fireEvent.click(within(player).getByRole('button', { name: 'Play' }));
    expect(audio.currentTime).toBe(0.8);
    expect(document.querySelector('[data-active]')?.textContent).toContain('Good morning.');
    fireEvent(audio, new Event('play'));

    // The seek bar released in the silence: the player lands on the same start.
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({
      left: 0,
      top: 0,
      width: 1185,
      height: 8,
      x: 0,
      y: 0,
    } as DOMRect);
    const seek = within(player).getByRole('slider', { name: 'Seek' });
    const root = required(required(seek.parentElement).parentElement);
    fireEvent.pointerDown(root, { pointerId: 7, clientX: 0 });
    fireEvent.pointerUp(root, { pointerId: 7, clientX: 0 });
    expect(audio.currentTime).toBe(0.8);
  });

  it('plays the last segment from its start after the audio has ended', async () => {
    // Discovered 2026-10-23: the media element rewinds to 0 when play() follows its end, so
    // Space on the still-current last segment would restart the whole clip; it plays that
    // segment instead. turns.json's last segment is [9600,14250).
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const last = screen.getByRole('button', { name: "Let's start with your background." });

    playheadAt(audio, 15.0); // past the end
    fireEvent(audio, new Event('ended'));
    vi.spyOn(audio, 'ended', 'get').mockReturnValue(true);
    expect(last).toHaveAttribute('data-active');

    last.focus();
    fireEvent.keyDown(last, { key: ' ' });
    expect(play).toHaveBeenCalledTimes(1);
    expect(audio.currentTime).toBe(9.6);
  });

  it("lets the editor field take the segment's own direction, not the paragraph's", async () => {
    // User decision 2026-10-23: `dir="auto"` on the field alone makes the browser pick the
    // direction from the segment's first strong character, whatever the paragraph does; closing
    // the editor leaves the segment with no direction of its own (the paragraph rule applies
    // again). jsdom does not resolve auto, so the attribute is what this pins — the browser
    // check is the e2e journey's.
    await renderTranscript('3');
    const field = openSegmentEditor('Thanks for joining us today.');
    expect(field).toHaveAttribute('dir', 'auto');

    fireEvent.keyDown(field, { key: 'Escape' });
    const segment = screen.getByRole('button', { name: 'Thanks for joining us today.' });
    expect(segment).not.toHaveAttribute('dir');
  });

  it("puts focus in the editor's field on opening it, the cursor after the last character", async () => {
    // The user's review 2026-10-23: pressing Edit must leave the user typing at once, with the
    // caret at the end of the segment's text — not on the Edit control, and not after the first
    // character.
    await renderTranscript('3');
    const field = openSegmentEditor('Thanks for joining us today.') as HTMLTextAreaElement;

    expect(document.activeElement).toBe(field);
    expect(field.selectionStart).toBe(field.value.length);
    expect(field.selectionEnd).toBe(field.value.length);
  });

  it('marks only the current segment with the fill; a staging focus adds no mark of its own', async () => {
    // Item 52, the user's trial model: one filled highlight (the playhead's segment); focus on
    // another segment is only the browser's focus ring — no fill, no second frame of ours.
    await renderTranscript('3');
    const first = screen.getByRole('button', { name: 'Good morning.' });
    const fourth = screen.getByRole('button', { name: "Let's start with your background." });

    fourth.focus();
    expect(fourth).not.toHaveAttribute('data-active');
    expect(getComputedStyle(fourth).backgroundColor).not.toBe(asRgb(lightTheme.colors.accent));
    expect(first).toHaveAttribute('data-active');
    expect(getComputedStyle(first).backgroundColor).toBe(asRgb(lightTheme.colors.accent));
    // The current segment carries no outline of ours either: the fill is the only mark.
    expect(getComputedStyle(first).outlineStyle).not.toBe('solid');
  });

  it("keeps the current segment's Edit control in the text line: inline, unpadded, its icon no taller than the text", async () => {
    // User report (item 52): a button box taller than the text raised the line height. An
    // inline box in the segment's own font is exactly the highlight's height; jsdom has no
    // layout, so this checks the styles that give it, and e2e spec 52 measures the result.
    await renderTranscript('3');
    fireEvent.click(screen.getByRole('button', { name: 'Thanks for joining us today.' }));

    const edit = screen.getByRole('button', { name: 'Edit segment' });
    expect(getComputedStyle(edit).display).toBe('inline');
    expect(getComputedStyle(edit).paddingTop).toBe('0px');
    expect(getComputedStyle(edit).paddingBottom).toBe('0px');
    const icon = required(edit.querySelector('svg'));
    expect(Number.parseFloat(getComputedStyle(icon).height)).toBeLessThanOrEqual(
      Number.parseFloat(px(lightTheme.fontSizes.body)),
    );
  });

  it("keeps the Edit control a word space apart from its segment's frame", async () => {
    // User report (item 52): the pencil touched the frame drawn around the segment. A left
    // margin of the theme's smallest spacing (0.25rem, about a word space) parts them; a margin,
    // not padding, so the control's hover and focus boxes stay around the icon. jsdom has no
    // layout, so this checks the style that gives the gap.
    await renderTranscript('3');
    fireEvent.click(screen.getByRole('button', { name: 'Thanks for joining us today.' }));

    const edit = screen.getByRole('button', { name: 'Edit segment' });
    expect(getComputedStyle(edit).marginLeft).toBe(px(lightTheme.spacing.xs));
    expect(getComputedStyle(edit).paddingLeft).toBe('0px');
  });

  it('flows the segments as one paragraph of text, each still a button that seeks on Enter and Space', async () => {
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());

    // A segment is inline text in its turn's paragraph, not a button box: a box (inline-block)
    // wraps as a whole and centers its own lines, so lines started at different positions and a
    // segment's last line was centered (item 29, user's browser review). jsdom does no layout,
    // so the computed styles stand in for the line breaks.
    const happy = screen.getByRole('button', { name: /Happy to be here\./ });
    expect(getComputedStyle(happy).display).toBe('inline');
    expect(getComputedStyle(happy).textAlign).not.toBe('center');

    // It stays keyboard-operable like a button: focusable, and Enter and Space seek like a click
    // (5800 ms and 2400 ms, the fixture's starts); Space does not scroll the page.
    expect(happy.tabIndex).toBe(0);
    fireEvent.keyDown(happy, { key: 'Enter' });
    expect(audio.currentTime).toBe(5.8);
    const thanks = screen.getByRole('button', { name: /Thanks for joining us today\./ });
    const scrolled = fireEvent.keyDown(thanks, { key: ' ' });
    expect(audio.currentTime).toBe(2.4);
    expect(scrolled).toBe(false);
  });

  it('keeps the highlighted segment in view while playing, stops on a hand scroll, and resumes', async () => {
    const scrollIntoView = stubScrollIntoView();
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const region = screen.getByRole('region', { name: /transcript/i });

    // While playing, the active segment is scrolled into view (follow mode).
    playheadAt(audio, 1.0);
    expect(scrollIntoView).toHaveBeenCalledTimes(1);

    // A hand scroll stops following: a "Back to playing position" button appears. A wheel
    // over the region is a hand scroll (item 36); a bare scroll event is not the user
    // scrolling — the view's own follow scroll fires one too — so it never stops following.
    fireEvent.wheel(region);
    expect(backButton()).toBeInTheDocument();

    // While stopped, playback no longer scrolls.
    scrollIntoView.mockClear();
    playheadAt(audio, 6.0);
    expect(scrollIntoView).not.toHaveBeenCalled();

    // "Back to playing position" resumes following and scrolls to the current segment.
    fireEvent.click(required(backButton()));
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
  });

  it('holds following when its own scroll event arrives after the next macrotask (the hold-off race)', async () => {
    const scrollIntoView = stubScrollIntoView();
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const region = screen.getByRole('region', { name: /transcript/i });

    // The playhead moves; the follow effect scrolls the active segment into view.
    playheadAt(audio, 1.0);
    expect(scrollIntoView).toHaveBeenCalledTimes(1);

    // The old mechanism cleared a setTimeout(0) hold-off flag and then read the view's
    // scroll event as the user's hand — a race the browser lost in 21 of 40 Chromium
    // trials (item 36): the scroll event arrived after the flag was cleared. Reproduce
    // that timing (scroll event after the next macrotask) on purpose: following holds.
    const { promise: tick, resolve: resolveTick } = Promise.withResolvers<void>();
    setTimeout(resolveTick, 0);
    await tick;
    fireEvent.scroll(region);

    expect(backButton()).not.toBeInTheDocument();
  });

  it('holds following through many consecutive follow scrolls while playing', async () => {
    const scrollIntoView = stubScrollIntoView();
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const region = screen.getByRole('region', { name: /transcript/i });

    // The playhead moves across several segments, each one a follow scroll; after every
    // scroll the scroll event arrives late, as it does in a browser. Following must hold
    // through all of them (item 36).
    const followCues = [1.0, 6.0, 10.0]; // inside [0,2100), [5800,9100) and [9600,14250).
    for (const [index, cueSeconds] of followCues.entries()) {
      playheadAt(audio, cueSeconds);
      const { promise: tick, resolve: resolveTick } = Promise.withResolvers<void>();
      setTimeout(resolveTick, 0);
      await tick;
      fireEvent.scroll(region);
      // Each cue is its own follow scroll: the call count grows one per cue.
      expect(scrollIntoView).toHaveBeenCalledTimes(index + 1);
      expect(backButton()).not.toBeInTheDocument();
    }
  });

  it('stops following on a wheel over the region: a hand scroll, even mid-follow', async () => {
    const scrollIntoView = stubScrollIntoView();
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const region = screen.getByRole('region', { name: /transcript/i });

    // The playhead is moving; the follow effect has fired (so following is active) when
    // the wheel lands on the region mid-follow.
    playheadAt(audio, 1.0);
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    playheadAt(audio, 6.0);

    fireEvent.wheel(region);
    expect(backButton()).toBeInTheDocument();

    // Stopped stays stopped: the advancing playhead no longer triggers follow scrolls.
    scrollIntoView.mockClear();
    playheadAt(audio, 10.0);
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('stops following on a scrollbar drag or the scroll keys, and only on input that scrolls', async () => {
    const scrollIntoView = stubScrollIntoView();
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const region = screen.getByRole('region', { name: /transcript/i });
    const returnToFollowing = () => {
      fireEvent.click(required(backButton()));
      expect(backButton()).not.toBeInTheDocument();
    };

    // A plain key does not scroll, so it does not stop following.
    playheadAt(audio, 1.0);
    fireEvent.keyDown(region, { key: 'a' });
    expect(backButton()).not.toBeInTheDocument();

    // A scroll key stops following (keyboard hand scroll, item 36).
    fireEvent.keyDown(region, { key: 'ArrowDown' });
    expect(backButton()).toBeInTheDocument();
    returnToFollowing();

    // A pointer press that scrolled nothing is not a scroll — neither a click nor a press
    // alone stops following.
    fireEvent.pointerDown(region, { pointerId: 1, buttons: 1 });
    fireEvent.pointerUp(region, { pointerId: 1, buttons: 1 });
    expect(backButton()).not.toBeInTheDocument();

    // A drag that scrolls the region is a scrollbar drag: the browser scrolls while the
    // button is held, so the pointer move that finds the scrolled position stops following.
    fireEvent.pointerDown(region, { pointerId: 2, buttons: 1 });
    region.scrollTop = 100;
    fireEvent.pointerMove(region, { pointerId: 2, buttons: 1 });
    expect(backButton()).toBeInTheDocument();
    fireEvent.pointerUp(region, { pointerId: 2, buttons: 1 });
    expect(backButton()).toBeInTheDocument();

    // Following is off; the playhead advancing no longer scrolls.
    scrollIntoView.mockClear();
    playheadAt(audio, 6.0);
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('resumes following from "Back to playing position" and scrolls to the playing segment', async () => {
    const scrollIntoView = stubScrollIntoView();
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const region = screen.getByRole('region', { name: /transcript/i });

    // Play, then a wheel stops following.
    playheadAt(audio, 1.0);
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    fireEvent.wheel(region);
    expect(backButton()).toBeInTheDocument();

    // While stopped, the playhead keeps moving — the highlight follows the playhead even
    // when the view is not scrolling — so the playing segment on resume is not the old one.
    playheadAt(audio, 6.0);

    // Resuming follows again and scrolls to the segment the playhead is in now.
    fireEvent.click(required(backButton()));
    expect(scrollIntoView).toHaveBeenCalledTimes(2);
    expect(required(document.querySelector('[data-active]')).textContent).toContain(
      'Happy to be here.',
    );
  });

  it('keeps the playing segment visible below the sticky header: the active segment carries a top scroll margin the size of the compact header', async () => {
    stubScrollIntoView();
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;

    // While playing, follow mode scrolls the active segment; the browser honors the
    // target's scroll margin for every scrolled ancestor, so the segment's top margin
    // reserves the compact header's height (the text-only header plus its border, all
    // tokens) and keeps it clear of the sticky header (item 31, item 59).
    playheadAt(audio, 1.0);
    const active = required(document.querySelector('[data-active]'));
    expect(getComputedStyle(active).scrollMarginTop).toBe(
      `${
        Number.parseFloat(lightTheme.sizes.compactHeader) * 16 +
        Number.parseFloat(lightTheme.borderWidths.thin) * 16
      }px`,
    );
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

    // The error message, Retry and the actions menu (Delete sits there in item 59), and
    // (hasAudio) the player.
    expect(screen.getByText(/failed to allocate memory/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Actions for call.ogg' })).toBeInTheDocument();
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
    expect(screen.getByRole('button', { name: /^Actions for / })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: /transcript/i })).not.toBeInTheDocument();
  });

  it('shows a not-found message for a missing task', async () => {
    // task 999 is not in the snapshot, as a deleted or never-uploaded task would be absent.
    await renderTranscript('999');

    expect(await screen.findByText('Task not found')).toBeInTheDocument();
    // No transcript, downloads, player or status controls are shown for a missing task.
    expect(screen.queryByRole('region', { name: /transcript/i })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Download transcript/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Audio/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
  });

  it('after retry on the status page, goes back to the queue', async () => {
    // Mock addition: retrying task 4 (FAILED, the default snapshot's task 4) returns it now
    // QUEUED, so the status page navigates to the queue.
    server.use(
      http.post('/api/tasks/:id/retry', ({ params }) => {
        const id = Number(params.id);
        const base = taskFailed;
        return HttpResponse.json({
          ...base,
          id,
          status: 'QUEUED',
          phase: null,
          error: null,
          finishedAt: null,
        });
      }),
    );
    await renderTranscript('4'); // task 4 is FAILED in the snapshot (id 4).

    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));

    // The retried task leaves History (it is QUEUED) and the view navigates to /queue.
    expect(
      await screen.findByRole('heading', { name: 'Queue' }, { timeout: NAVIGATION_TIMEOUT_MS }),
    ).toBeInTheDocument();
    expect(screen.queryByText('Not found')).not.toBeInTheDocument();
  });

  it('shows "still in the queue" for a pending task opened directly', async () => {
    // task 5 is RUNNING and task 7 is QUEUED in the default snapshot.
    await renderTranscript('5');

    expect(await screen.findByText(/still in the queue/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /go to the queue/i })).toHaveAttribute(
      'href',
      '/queue',
    );
    // No transcript, downloads or player for a task that has not finished.
    expect(screen.queryByRole('region', { name: /transcript/i })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Download transcript/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Audio/)).not.toBeInTheDocument();
  });

  it('deletes from the status page with confirmation, then returns to the history list', async () => {
    // Mock addition: the delete handler records the ids it receives (the stock handler
    // answers 204 without recording).
    const deleted: number[] = [];
    server.use(
      http.delete('/api/tasks/:id', ({ params }) => {
        deleted.push(Number(params.id));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await renderTranscript('4'); // task 4 is FAILED.

    fireEvent.pointerDown(await screen.findByRole('button', { name: 'Actions for call.ogg' }), {
      button: 0,
    });
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }));
    let dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('call.ogg');

    // Cancel sends nothing and keeps the page.
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(deleted).toEqual([]);

    // Confirming sends DELETE /api/tasks/4 and lands back on the history list.
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Actions for call.ogg' }), {
      button: 0,
    });
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }));
    dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
    await waitFor(
      () => expect(screen.getByRole('heading', { name: 'History' })).toBeInTheDocument(),
      { timeout: NAVIGATION_TIMEOUT_MS },
    );
    expect(deleted).toEqual([4]);
  });

  it('deletes from the transcript view with confirmation, then returns to the history list', async () => {
    const deleted: number[] = [];
    server.use(
      http.delete('/api/tasks/:id', ({ params }) => {
        deleted.push(Number(params.id));
        return new HttpResponse(null, { status: 204 });
      }),
    );
    await renderTranscript('3'); // task 3 is COMPLETED.

    fireEvent.pointerDown(
      await screen.findByRole('button', { name: 'Actions for interview.mp3' }),
      { button: 0 },
    );
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('interview.mp3');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    await waitFor(
      () => expect(screen.getByRole('heading', { name: 'History' })).toBeInTheDocument(),
      { timeout: NAVIGATION_TIMEOUT_MS },
    );
    expect(deleted).toEqual([3]);
  });

  it('renames a speaker from the speakers panel: every turn shows the new name; a rejected name keeps the old one', async () => {
    // Mock addition (item 22): the rename stores names like the backend (starting from the
    // turns.json speakers) and answers them in speakers.json's shape, or 409
    // duplicate-speaker-name for a name another speaker has; error.json stands in for the
    // 409 body, with its code changed.
    const sent: { speakerId: number; body: unknown }[] = [];
    let stored = turnsFixture.speakers;
    server.use(
      http.put('/api/tasks/:id/speakers/:speakerId', async ({ params, request }) => {
        const speakerId = Number(params.speakerId);
        const body = (await request.json()) as { speaker: string };
        sent.push({ speakerId, body });
        const others = stored.filter((s) => s.speakerId !== speakerId);
        if (others.some((s) => s.speaker.toLowerCase() === body.speaker.toLowerCase())) {
          return HttpResponse.json(
            { ...errorFixture, error: 'duplicate-speaker-name' },
            { status: 409 },
          );
        }
        stored = stored.map((s) =>
          s.speakerId === speakerId ? { ...s, speaker: body.speaker } : s,
        );
        return HttpResponse.json({ speakers: stored });
      }),
    );
    await renderTranscript('3'); // task 3 is COMPLETED; turns.json: speaker 0 speaks twice.
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    const panel = screen.getByRole('region', { name: 'Speakers' });

    // The panel trims before sending (user decision 2026-10-10); Enter saves.
    const first = within(panel).getByRole('textbox', { name: 'Name of speaker 1' });
    expect(first).toHaveValue('Speaker 1');
    fireEvent.change(first, { target: { value: '  Alice  ' } });
    fireEvent.keyDown(first, { key: 'Enter' });

    // Both of speaker 0's turns now carry the new name.
    await waitFor(() => expect(within(transcript).getAllByText('Alice')).toHaveLength(2));
    expect(within(transcript).queryByText('Speaker 1')).not.toBeInTheDocument();
    expect(sent).toEqual([{ speakerId: 0, body: { speaker: 'Alice' } }]);
    expect(first).toHaveValue('Alice');

    // A rejected name (blur saves too) keeps the previous one and shows the error.
    const second = within(panel).getByRole('textbox', { name: 'Name of speaker 2' });
    fireEvent.change(second, { target: { value: 'alice' } });
    fireEvent.blur(second);
    expect(await within(panel).findByText(errorFixture.message)).toBeInTheDocument();
    expect(second).toHaveValue('Speaker 2');
    expect(within(transcript).getByText('Speaker 2')).toBeInTheDocument();
  });

  it('merges a group into a chosen survivor after a confirmation, and "Reset all merges" undoes it', async () => {
    // Mock addition (item 22): the merge and reset endpoints switch /turns between turns.json
    // and the turns merged by hand ({0, 1} into 0: one turn of speaker 0 over all four
    // segments, hasMerges true), as the backend's read path does.
    const merged = {
      speakers: [turnsFixture.speakers[0]!],
      hasMerges: true,
      turns: [
        {
          speakerId: 0,
          startMs: 0,
          endMs: 14250,
          text: turnsFixture.turns.map((turn) => turn.text).join(' '),
          segments: turnsFixture.turns.flatMap((turn) => turn.segments),
        },
      ],
    };
    let isMerged = false;
    const posted: unknown[] = [];
    let resets = 0;
    server.use(
      http.get('/api/tasks/:id/turns', () =>
        HttpResponse.json(isMerged ? merged : { ...turnsFixture, hasMerges: false }),
      ),
      http.post('/api/tasks/:id/speakers/merge', async ({ request }) => {
        posted.push(await request.json());
        isMerged = true;
        return HttpResponse.json({ speakers: merged.speakers });
      }),
      http.delete('/api/tasks/:id/speakers/merges', () => {
        resets += 1;
        isMerged = false;
        return HttpResponse.json({ speakers: turnsFixture.speakers });
      }),
    );
    await renderTranscript('3'); // task 3 is COMPLETED; turns.json has speakers 0 and 1.
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    const panel = screen.getByRole('region', { name: 'Speakers' });
    // Nothing to undo yet.
    expect(
      within(panel).queryByRole('button', { name: 'Reset all merges' }),
    ).not.toBeInTheDocument();

    // Pick the group and the survivor; the confirmation quotes the spec's wording.
    fireEvent.click(within(panel).getByRole('button', { name: 'Merge speakers…' }));
    fireEvent.click(within(panel).getByRole('checkbox', { name: 'Speaker 1' }));
    fireEvent.click(within(panel).getByRole('checkbox', { name: 'Speaker 2' }));
    fireEvent.change(within(panel).getByRole('combobox', { name: 'Keep' }), {
      target: { value: '0' },
    });
    fireEvent.click(within(panel).getByRole('button', { name: 'Merge' }));
    expect(screen.getByRole('dialog')).toHaveTextContent(
      'Merge Speaker 2 into Speaker 1? Their turns join Speaker 1; you can undo this.',
    );

    // Cancel sends nothing; only a confirmed merge is sent.
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(posted).toEqual([]);
    fireEvent.click(within(panel).getByRole('button', { name: 'Merge' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Merge' }));

    // Speaker 2's turn joins Speaker 1's: one turn, one label; Reset all merges appears.
    await waitFor(() => expect(within(transcript).getAllByText('Speaker 1')).toHaveLength(1));
    expect(posted).toEqual([{ group: [0, 1], into: 0 }]);
    expect(within(transcript).queryByText('Speaker 2')).not.toBeInTheDocument();
    expect(
      within(panel).queryByRole('textbox', { name: 'Name of speaker 2' }),
    ).not.toBeInTheDocument();

    // Reset all merges brings the speaker and its turn back, and has nothing left to undo.
    fireEvent.click(await within(panel).findByRole('button', { name: 'Reset all merges' }));
    expect(await within(transcript).findByText('Speaker 2')).toBeInTheDocument();
    expect(within(transcript).getAllByText('Speaker 1')).toHaveLength(2);
    expect(resets).toBe(1);
    expect(
      within(panel).queryByRole('button', { name: 'Reset all merges' }),
    ).not.toBeInTheDocument();
  });

  it('applies a speakers-renamed event to the open view and refetches /turns on speakers-merged', async () => {
    // The speakers events of item 22 (docs/api/speakers-*.json shapes), pushed after the
    // snapshot. Mock addition: /turns answers turns.json until the server-side merge, then the
    // turns merged by hand ({0, 1} into 0, speaker 0 already named Alice by the rename event).
    const merged = {
      speakers: [{ speakerId: 0, speaker: 'Alice' }],
      hasMerges: true,
      turns: [
        {
          speakerId: 0,
          startMs: 0,
          endMs: 14250,
          text: turnsFixture.turns.map((turn) => turn.text).join(' '),
          segments: turnsFixture.turns.flatMap((turn) => turn.segments),
        },
      ],
    };
    let isMerged = false;
    const turnsRequests = countTurnsRequests(() => (isMerged ? merged : turnsFixture));
    const events = controllableEventStream();
    await renderTranscript('3'); // task 3 is COMPLETED; turns.json has speakers 0 and 1.
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    const panel = screen.getByRole('region', { name: 'Speakers' });
    expect(turnsRequests()).toBe(1);

    // Another task's rename comes first and must change nothing here; the stream keeps its
    // order, so once this task's rename shows, the other was applied.
    events.send('speakers-renamed', { id: 5, speakers: [{ speakerId: 0, speaker: 'Zoe' }] });
    // A rename elsewhere: its names land in the panel and every turn, with no refetch.
    events.send('speakers-renamed', {
      id: 3,
      speakers: [
        { speakerId: 0, speaker: 'Alice' },
        { speakerId: 1, speaker: 'Speaker 2' },
      ],
    });
    await waitFor(() => expect(within(transcript).getAllByText('Alice')).toHaveLength(2));
    expect(within(panel).getByRole('textbox', { name: 'Name of speaker 1' })).toHaveValue('Alice');
    expect(within(transcript).queryByText('Zoe')).not.toBeInTheDocument();
    expect(turnsRequests()).toBe(1);

    // A merge elsewhere: turns can join, so the view reads /turns again.
    isMerged = true;
    events.send('speakers-merged', { id: 3, speakers: merged.speakers });
    await waitFor(() =>
      expect(within(transcript).queryByText('Speaker 2')).not.toBeInTheDocument(),
    );
    expect(turnsRequests()).toBe(2);
    expect(within(transcript).getAllByText('Alice')).toHaveLength(1);
    expect(within(panel).getByRole('button', { name: 'Reset all merges' })).toBeInTheDocument();
  });

  it('applies a segment-edited event to the open view in place, without refetching /turns', async () => {
    // Item 52 (docs/api/segment-edited.json shape): a stored edit names its task and the one
    // segment it changed.
    const turnsRequests = countTurnsRequests();
    const events = controllableEventStream();
    await renderTranscript('3'); // task 3 is COMPLETED; turns.json's segment 2 is "Thanks for joining us today."
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    expect(await within(transcript).findByText('Thanks for joining us today.')).toBeInTheDocument();
    expect(turnsRequests()).toBe(1);

    // Another task's edit of a segment with the same id comes first and must change nothing
    // here; the stream keeps its order, so once this task's edit shows, the other was applied.
    events.send('segment-edited', {
      id: 5,
      segment: { id: 2, startMs: 2400, endMs: 5200, text: 'Not this transcript.' },
    });
    events.send('segment-edited', {
      id: 3,
      segment: { id: 2, startMs: 2400, endMs: 5200, text: 'Thanks for joining us, everyone.' },
    });

    expect(
      await within(transcript).findByText('Thanks for joining us, everyone.'),
    ).toBeInTheDocument();
    expect(within(transcript).queryByText('Thanks for joining us today.')).not.toBeInTheDocument();
    expect(within(transcript).queryByText('Not this transcript.')).not.toBeInTheDocument();
    // The rest is as loaded: the turn's other segment and the next turn.
    expect(within(transcript).getByText('Good morning.')).toBeInTheDocument();
    expect(within(transcript).getByText('Happy to be here.')).toBeInTheDocument();
    expect(turnsRequests()).toBe(1);
  });

  it('shows both of two segment edits of the open transcript that arrive in one stream chunk', async () => {
    // Discovered in item 52: one network read can carry several events, which EventSource
    // then dispatches before the page renders again; an edit of one segment must not hide an
    // edit of another. turns.json's segments 1 and 2 are task 3's first turn.
    const events = controllableEventStream();
    await renderTranscript('3');
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    expect(await within(transcript).findByText('Good morning.')).toBeInTheDocument();

    events.sendTogether([
      [
        'segment-edited',
        { id: 3, segment: { id: 1, startMs: 0, endMs: 2100, text: 'Good morning, all.' } },
      ],
      [
        'segment-edited',
        { id: 3, segment: { id: 2, startMs: 2400, endMs: 5200, text: 'Thanks for coming.' } },
      ],
    ]);

    expect(await within(transcript).findByText('Thanks for coming.')).toBeInTheDocument();
    expect(within(transcript).getByText('Good morning, all.')).toBeInTheDocument();
  });

  it('resets a speaker name from the panel: "Speaker N" comes back in the field and every turn', async () => {
    // Mock addition (item 22): the rename stores names like the backend; `null` stores the
    // default "Speaker N" (N = speakerId + 1), which only the server writes.
    const sent: unknown[] = [];
    let stored = turnsFixture.speakers;
    server.use(
      http.put('/api/tasks/:id/speakers/:speakerId', async ({ params, request }) => {
        const speakerId = Number(params.speakerId);
        const body = (await request.json()) as { speaker: string | null };
        sent.push(body);
        const name = body.speaker ?? `Speaker ${speakerId + 1}`;
        stored = stored.map((s) => (s.speakerId === speakerId ? { ...s, speaker: name } : s));
        return HttpResponse.json({ speakers: stored });
      }),
    );
    await renderTranscript('3'); // task 3 is COMPLETED; turns.json: speaker 0 speaks twice.
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    const panel = screen.getByRole('region', { name: 'Speakers' });
    const field = within(panel).getByRole('textbox', { name: 'Name of speaker 1' });
    fireEvent.change(field, { target: { value: 'Alice' } });
    fireEvent.keyDown(field, { key: 'Enter' });
    await waitFor(() => expect(within(transcript).getAllByText('Alice')).toHaveLength(2));

    fireEvent.click(within(panel).getByRole('button', { name: 'Reset name of speaker 1' }));

    await waitFor(() => expect(within(transcript).getAllByText('Speaker 1')).toHaveLength(2));
    expect(within(transcript).queryByText('Alice')).not.toBeInTheDocument();
    expect(field).toHaveValue('Speaker 1');
    expect(sent).toEqual([{ speaker: 'Alice' }, { speaker: null }]);
  });
});

describe('the transcript loading states', () => {
  it('renders nothing of its own before the snapshot, then the known parts with the mascot and a label in place of the turns until they arrive', async () => {
    // The turns answer is held back until the test releases it.
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.get('/api/tasks/:id/turns', async () => {
        await held;
        return HttpResponse.json(turnsFixture);
      }),
    );
    const live = renderTranscript('3');

    // Before the snapshot the shell's connecting region is the loading state; the view
    // renders nothing of its own (no second mascot, no bare "Loading…").
    expect(screen.getByRole('main').textContent).toBe('');
    await live;

    // The task is known: its downloads and Delete are there at once, and the mascot with
    // a label stands where the turns will appear.
    const label = await screen.findByText('Loading transcript…');
    const mascot = required(required(label.parentElement).querySelector('svg'));
    expect(getComputedStyle(mascot).width).toBe(px(lightTheme.sizes.stenoEmpty));
    expect(getComputedStyle(label).color).toBe(asRgb(lightTheme.colors.textMuted));
    expect(screen.getByRole('link', { name: 'Download transcript (txt)' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Actions for interview.mp3' })).toBeInTheDocument();
    expect(screen.queryByText('Good morning.')).not.toBeInTheDocument();

    // The turns arrive and take its place.
    release();
    expect(await screen.findByText('Good morning.')).toBeInTheDocument();
    expect(screen.queryByText('Loading transcript…')).not.toBeInTheDocument();
  });

  it('shows an edit that arrived while the transcript was loading, once it has loaded', async () => {
    // Discovered in item 52: the /turns read ran before an edit was stored elsewhere, and the
    // edit's event arrives while that answer is still on its way, so the answer holds the old
    // text. Mock addition: /turns pushes the event, lets the stream deliver it (one macrotask),
    // then answers turns.json, whose segment 2 is "Thanks for joining us today."
    const events = controllableEventStream();
    server.use(
      http.get('/api/tasks/:id/turns', async () => {
        events.send('segment-edited', {
          id: 3,
          segment: { id: 2, startMs: 2400, endMs: 5200, text: 'Thanks for joining us, everyone.' },
        });
        await new Promise((resolve) => setTimeout(resolve, 0));
        return HttpResponse.json(turnsFixture);
      }),
    );
    await renderTranscript('3');

    expect(await screen.findByText('Thanks for joining us, everyone.')).toBeInTheDocument();
    expect(screen.queryByText('Thanks for joining us today.')).not.toBeInTheDocument();
  });
});

describe('the transcript error states', () => {
  it('shows the error callout with the server detail when the turns fail to load, and retries in place', async () => {
    // The first turns request fails with the contract's error body; the retry succeeds.
    let requests = 0;
    server.use(
      http.get('/api/tasks/:id/turns', () => {
        requests += 1;
        return requests === 1
          ? HttpResponse.json(errorFixture, { status: 404 })
          : HttpResponse.json(turnsFixture);
      }),
    );
    await renderTranscript('3');

    // The callout: a plain title, then the server's message as a muted detail line, on the
    // surface fill with a danger bar on its left, announced as an alert.
    const callout = await screen.findByRole('alert');
    const title = within(callout).getByText("Couldn't load the transcript");
    expect(getComputedStyle(title).color).toBe(asRgb(lightTheme.colors.text));
    const detail = within(callout).getByText('Task 42 does not exist');
    expect(getComputedStyle(detail).color).toBe(asRgb(lightTheme.colors.textMuted));
    const box = getComputedStyle(callout);
    expect(box.backgroundColor).toBe(asRgb(lightTheme.colors.surface));
    expect(box.borderLeftColor).toBe(asRgb(lightTheme.colors.danger));
    expect(box.borderLeftWidth).toBe(px(lightTheme.borderWidths.ring));
    expect(required(callout.querySelector('svg')).closest('[aria-hidden="true"]')).not.toBeNull();

    // The known parts stay; no loading mascot lingers in place of the turns.
    expect(screen.getByRole('link', { name: 'Download transcript (txt)' })).toBeInTheDocument();
    expect(screen.queryByText('Loading transcript…')).not.toBeInTheDocument();

    // Retry refetches in place: the turns arrive and the callout leaves.
    fireEvent.click(within(callout).getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Good morning.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(requests).toBe(2);
  });
});

describe('the transcript header block', () => {
  it("shows the task's name as the heading and its finish date and duration under it", async () => {
    // finishedAt 1791537645000 -> "Oct 9, 2026" (UTC); durationMs 1185000 -> "19:45" (mm:ss),
    // the History card's format.
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          [
            'snapshot',
            {
              tasks: [{ ...taskCompleted, id: 3, finishedAt: 1791537645000, durationMs: 1185000 }],
              queue: { paused: false, taskIds: [] },
            },
          ],
        ]),
      ),
    );
    await renderTranscript('3');

    const heading = await screen.findByRole('heading', { level: 2, name: 'interview.mp3' });
    const meta = screen.getByText('Oct 9, 2026 · 19:45');
    expect(heading.nextElementSibling).toBe(meta);
    expect(getComputedStyle(meta).color).toBe(asRgb(lightTheme.colors.textMuted));
    expect(screen.queryByRole('heading', { name: 'Transcript' })).not.toBeInTheDocument();
  });
});

describe('the themed player', () => {
  it('plays and pauses with an icon button, seeks with the Slider, shows the current and total time, and the highlight follows without native controls', async () => {
    // durationMs 1185000 -> total "19:45" and a seek bar of 1185 seconds.
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          [
            'snapshot',
            {
              tasks: [{ ...taskCompleted, id: 3, durationMs: 1185000 }],
              queue: { paused: false, taskIds: [] },
            },
          ],
        ]),
      ),
    );
    await renderTranscript('3');
    await screen.findByText('Good morning.');

    // The audio element stays (it plays and reports the playhead) but shows no native
    // controls; the themed player is a group on the surface fill.
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    expect(audio).not.toHaveAttribute('controls');
    const player = screen.getByRole('group', { name: 'Audio player' });
    expect(getComputedStyle(player).backgroundColor).toBe(asRgb(lightTheme.colors.surface));

    // Play/pause is an icon button that follows the audio element's own events.
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    fireEvent.click(within(player).getByRole('button', { name: 'Play' }));
    expect(play).toHaveBeenCalledTimes(1);
    fireEvent(audio, new Event('play'));
    fireEvent.click(within(player).getByRole('button', { name: 'Pause' }));
    expect(pause).toHaveBeenCalledTimes(1);
    fireEvent(audio, new Event('pause'));
    expect(within(player).getByRole('button', { name: 'Play' })).toBeInTheDocument();

    // The seek bar is item 24's Slider in seconds; the labels read current / total, muted.
    const seek = within(player).getByRole('slider', { name: 'Seek' });
    expect(seek).toHaveAttribute('aria-valuemax', '1185');
    expect(within(player).getByText('00:00 / 19:45')).toBeInTheDocument();
    playheadAt(audio, 6.0); // 6000 ms, inside turn 2 [5800,9100)
    expect(seek).toHaveAttribute('aria-valuenow', '6');
    const times = within(player).getByText('00:06 / 19:45');
    expect(getComputedStyle(times).color).toBe(asRgb(lightTheme.colors.textMuted));
    expect(document.querySelector('[data-active]')?.textContent).toContain('Happy to be here.');

    // Seeking with the Slider moves the audio and the highlight: one second back is
    // 5000 ms, inside [2400,5200).
    fireEvent.keyDown(seek, { key: 'ArrowLeft' });
    expect(audio.currentTime).toBe(5);
    expect(document.querySelector('[data-active]')?.textContent).toContain(
      'Thanks for joining us today.',
    );
    expect(within(player).getByText('00:05 / 19:45')).toBeInTheDocument();
  });

  it('follows a drag on the seek bar without seeking, and seeks once when the drag is released', async () => {
    // durationMs 1185000 -> total "19:45" and a seek bar of 1185 seconds.
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          [
            'snapshot',
            {
              tasks: [{ ...taskCompleted, id: 3, durationMs: 1185000 }],
              queue: { paused: false, taskIds: [] },
            },
          ],
        ]),
      ),
    );
    await renderTranscript('3');
    await screen.findByText('Good morning.');

    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const player = screen.getByRole('group', { name: 'Audio player' });
    const seek = within(player).getByRole('slider', { name: 'Seek' });

    // The audio plays and the playhead advances while the user drags.
    vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    fireEvent.click(within(player).getByRole('button', { name: 'Play' }));
    fireEvent(audio, new Event('play'));
    playheadAt(audio, 6.0); // 6000 ms, inside turn 2 [5800,9100)
    expect(seek).toHaveAttribute('aria-valuenow', '6');

    // Radix maps clientX to a value via the slider's bounding rect (pointer capture is
    // stubbed in test/setup.ts, like the ResizeObserver). The slider is 1185 px wide, so
    // 1 px = 1 second.
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue({
      left: 0,
      top: 0,
      width: 1185,
      height: 8,
      x: 0,
      y: 0,
    } as DOMRect);
    try {
      // Radix binds its pointer handlers on the slider's root span (the thumb's parent's
      // parent — the layout in Slider.test.tsx); a pointer-down there starts a slide, as
      // on the track. Drag from 4 s to 5 s: a drag tick is not a seek, and the thumb
      // follows the drag, not the moving playhead.
      const root = required(required(seek.parentElement).parentElement);
      fireEvent.pointerDown(root, { pointerId: 7, clientX: 4 });
      fireEvent.pointerMove(root, { pointerId: 7, clientX: 5 });
      expect(audio.currentTime).toBe(6.0);

      // Still dragging: the playhead keeps advancing, but the thumb stays on the draft
      // position and the audio is not seeked.
      playheadAt(audio, 6.4);
      expect(seek).toHaveAttribute('aria-valuenow', '5');
      expect(audio.currentTime).toBe(6.4);

      // Releasing seeks exactly once, to the released position: 5000 ms, inside
      // [2400,5200); the highlight and the time label follow.
      fireEvent.pointerUp(root, { pointerId: 7, clientX: 5 });
      expect(audio.currentTime).toBe(5);
      expect(seek).toHaveAttribute('aria-valuenow', '5');
      expect(document.querySelector('[data-active]')?.textContent).toContain(
        'Thanks for joining us today.',
      );
      expect(within(player).getByText('00:05 / 19:45')).toBeInTheDocument();
    } finally {
      vi.restoreAllMocks();
    }
  });

  it('offers Reassign speakers before Merge speakers…, and for a task with one speaker too', async () => {
    // Item 73's amendment (user decision 2026-10-31): reassigning is the general correction, merging
    // only helps a wrongly split speaker, so Reassign comes first and needs no second speaker.
    await renderTranscript('3');
    const panel = await screen.findByRole('region', { name: 'Speakers' });
    await within(panel).findByRole('button', { name: 'Merge speakers…' });
    const labels = within(panel)
      .getAllByRole('button')
      .map((button) => button.textContent);
    expect(labels.indexOf('Reassign speakers')).toBeGreaterThanOrEqual(0);
    expect(labels.indexOf('Reassign speakers')).toBeLessThan(labels.indexOf('Merge speakers…'));

    // One speaker: merging has nothing to merge, reassigning is the main case to correct.
    server.use(
      http.get('/api/tasks/:id/turns', () =>
        HttpResponse.json({
          speakers: [{ speakerId: 0, speaker: 'Speaker 1' }],
          hasMerges: false,
          turns: [
            { ...turnsFixture.turns[0]!, speakerId: 0 },
            { ...turnsFixture.turns[1]!, speakerId: 0 },
          ],
        }),
      ),
    );
    cleanup();
    await renderTranscript('3');
    const single = await screen.findByRole('region', { name: 'Speakers' });
    expect(await within(single).findByRole('button', { name: 'Reassign speakers' })).toBeVisible();
    expect(
      within(single).queryByRole('button', { name: 'Merge speakers…' }),
    ).not.toBeInTheDocument();
  });

  it('shows the reassignment boxes and bar only in the mode, and counts the selected segments', async () => {
    await renderTranscript('3');
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    const panel = screen.getByRole('region', { name: 'Speakers' });
    expect(await within(panel).findByRole('button', { name: 'Reassign speakers' })).toBeVisible();
    // Nothing to tick until the mode starts.
    expect(within(transcript).queryAllByRole('checkbox')).toHaveLength(0);

    fireEvent.click(within(panel).getByRole('button', { name: 'Reassign speakers' }));
    const bar = screen.getByRole('region', { name: 'Reassign speakers' });
    expect(bar).toHaveTextContent('Select segments to move to a different speaker');
    expect(bar).toHaveTextContent('0 segments selected');
    expect(within(bar).getByRole('button', { name: 'Move to…' })).toBeDisabled();
    // A box beside every segment (4) and every turn (3).
    expect(within(transcript).getAllByRole('checkbox')).toHaveLength(7);
    expect(
      within(transcript).getByRole('checkbox', { name: 'Select segment starting 00:02' }),
    ).toBeInTheDocument();
    expect(
      within(transcript).getByRole('checkbox', { name: 'Select turn starting 00:09' }),
    ).toBeInTheDocument();

    fireEvent.click(
      within(transcript).getByRole('checkbox', { name: 'Select segment starting 00:02' }),
    );
    expect(bar).toHaveTextContent('1 segment selected');
    expect(within(bar).getByRole('button', { name: 'Move to…' })).toBeEnabled();
  });

  it('moves the selected segments to the chosen speaker and ends the mode', async () => {
    // Item 73's amendment. Mock addition: /turns serves turns.json until the move, then segment 2
    // under a new speaker 2 ("Speaker 3"), as the backend then reads.
    const moved: Turns = {
      speakers: [...turnsFixture.speakers, { speakerId: 2, speaker: 'Speaker 3' }],
      hasMerges: false,
      turns: [
        {
          speakerId: 0,
          startMs: 0,
          endMs: 2100,
          text: 'Good morning.',
          segments: [{ id: 1, startMs: 0, endMs: 2100, text: 'Good morning.', edited: false }],
        },
        {
          speakerId: 2,
          startMs: 2400,
          endMs: 5200,
          text: 'Thanks for joining us today.',
          segments: [
            {
              id: 2,
              startMs: 2400,
              endMs: 5200,
              text: 'Thanks for joining us today.',
              edited: false,
            },
          ],
        },
        turnsFixture.turns[1]!,
        turnsFixture.turns[2]!,
      ],
    };
    let isMoved = false;
    const posted: { url: string; body: unknown }[] = [];
    server.use(
      http.get('/api/tasks/:id/turns', () => HttpResponse.json(isMoved ? moved : turnsFixture)),
      http.post('/api/tasks/:id/speakers/reassign', async ({ request, params }) => {
        posted.push({ url: String(params.id), body: await request.json() });
        isMoved = true;
        return HttpResponse.json({ speakers: moved.speakers });
      }),
    );
    await renderTranscript('3');
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    const panel = screen.getByRole('region', { name: 'Speakers' });
    fireEvent.click(await within(panel).findByRole('button', { name: 'Reassign speakers' }));
    const bar = screen.getByRole('region', { name: 'Reassign speakers' });
    fireEvent.click(
      within(transcript).getByRole('checkbox', { name: 'Select segment starting 00:02' }),
    );

    // "Move to…" offers the shown speakers and a new one; "New speaker" is the default.
    fireEvent.click(within(bar).getByRole('button', { name: 'Move to…' }));
    const picker = within(bar).getByRole('combobox', { name: 'Move to speaker' });
    expect(within(picker).getByRole('option', { name: 'New speaker' })).toBeInTheDocument();
    expect(within(picker).getByRole('option', { name: 'Speaker 1' })).toBeInTheDocument();
    expect(within(picker).getByRole('option', { name: 'Speaker 2' })).toBeInTheDocument();
    fireEvent.click(within(bar).getByRole('button', { name: 'Move' }));

    // The request carries the ticked segment and a null target; the view reads /turns again and
    // shows the moved segment under the new speaker, and the mode is over.
    await waitFor(() => expect(within(transcript).getByText('Speaker 3')).toBeInTheDocument());
    expect(posted).toEqual([{ url: '3', body: { segmentIds: [2], speakerId: null } }]);
    expect(screen.queryByRole('region', { name: 'Reassign speakers' })).not.toBeInTheDocument();
    expect(within(transcript).queryAllByRole('checkbox')).toHaveLength(0);
  });

  it("ticks all of a turn's segments from its box, and clears them when all are ticked", async () => {
    await renderTranscript('3');
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    const panel = screen.getByRole('region', { name: 'Speakers' });
    fireEvent.click(await within(panel).findByRole('button', { name: 'Reassign speakers' }));
    const bar = screen.getByRole('region', { name: 'Reassign speakers' });
    const box = (name: string) => within(transcript).getByRole('checkbox', { name });
    const firstTurn = box('Select turn starting 00:00');
    const firstSegment = box('Select segment starting 00:00');
    const secondSegment = box('Select segment starting 00:02');
    const thirdSegment = box('Select segment starting 00:05');

    // The first turn holds segments 1 and 2: its box ticks both.
    fireEvent.click(firstTurn);
    expect(firstTurn).toBeChecked();
    expect(firstSegment).toBeChecked();
    expect(secondSegment).toBeChecked();
    expect(bar).toHaveTextContent('2 segments selected');

    // The second turn holds segment 3 alone; the count adds it.
    fireEvent.click(box('Select turn starting 00:05'));
    expect(thirdSegment).toBeChecked();
    expect(bar).toHaveTextContent('3 segments selected');

    // Ticking every segment of the first turn already, its box clears them and nothing else.
    fireEvent.click(firstTurn);
    expect(firstTurn).not.toBeChecked();
    expect(firstSegment).not.toBeChecked();
    expect(secondSegment).not.toBeChecked();
    expect(thirdSegment).toBeChecked();
    expect(bar).toHaveTextContent('1 segment selected');
  });

  it('leaves the mode with Cancel or Escape, Escape closing the picker first', async () => {
    await renderTranscript('3');
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    const panel = screen.getByRole('region', { name: 'Speakers' });
    const start = () =>
      fireEvent.click(within(panel).getByRole('button', { name: 'Reassign speakers' }));

    // Escape leaves the mode, with the selection dropped and nothing sent.
    start();
    fireEvent.click(
      within(transcript).getByRole('checkbox', { name: 'Select segment starting 00:02' }),
    );
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('region', { name: 'Reassign speakers' })).not.toBeInTheDocument();
    expect(within(transcript).queryAllByRole('checkbox')).toHaveLength(0);

    // Entering it again starts from nothing ticked.
    start();
    const bar = screen.getByRole('region', { name: 'Reassign speakers' });
    expect(bar).toHaveTextContent('0 segments selected');

    // With the picker open, Escape closes only the picker.
    fireEvent.click(
      within(transcript).getByRole('checkbox', { name: 'Select segment starting 00:02' }),
    );
    fireEvent.click(within(bar).getByRole('button', { name: 'Move to…' }));
    expect(within(bar).getByRole('combobox', { name: 'Move to speaker' })).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(
      within(bar).queryByRole('combobox', { name: 'Move to speaker' }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Reassign speakers' })).toBeInTheDocument();

    // Cancel leaves it too, and the transcript is untouched.
    fireEvent.click(within(bar).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('region', { name: 'Reassign speakers' })).not.toBeInTheDocument();
    expect((await within(transcript).findAllByText('Speaker 1')).length).toBe(2);
  });

  it('stops everything else while the mode is on, and pauses playback when it starts', async () => {
    await renderTranscript('3');
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const play = vi.spyOn(audio, 'play').mockImplementation(() => Promise.resolve());
    const pause = vi.spyOn(audio, 'pause').mockImplementation(() => undefined);
    const player = screen.getByRole('group', { name: 'Audio player' });
    const panel = screen.getByRole('region', { name: 'Speakers' });
    const transcript = screen.getByRole('region', { name: 'Transcript' });

    // Playing, with a current segment whose controls are on show.
    fireEvent.click(within(player).getByRole('button', { name: 'Play' }));
    fireEvent(audio, new Event('play'));
    fireEvent.click(within(transcript).getByText('Thanks for joining us today.'));
    fireEvent(audio, new Event('pause'));

    // Starting the mode pauses playback and takes every other control away.
    fireEvent.click(await within(panel).findByRole('button', { name: 'Reassign speakers' }));
    expect(pause).toHaveBeenCalled();
    expect(within(player).getByRole('button', { name: 'Play' })).toBeDisabled();
    expect(within(panel).getByRole('textbox', { name: 'Name of speaker 1' })).toBeDisabled();
    fireEvent.click(within(transcript).getByText('Thanks for joining us today.'));
    expect(
      within(transcript).queryByRole('button', { name: 'Edit segment' }),
    ).not.toBeInTheDocument();
    expect(
      within(transcript).queryByRole('button', { name: 'Play segment' }),
    ).not.toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Merge speakers…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^Actions for / })).toBeDisabled();

    // The segments themselves are inert too: a click does not seek, a double-click and Enter or
    // Space start nothing, and the text is no button role any more.
    play.mockClear();
    const atModeStart = audio.currentTime;
    const segmentText = within(transcript).getByText('Thanks for joining us today.');
    fireEvent.click(segmentText);
    fireEvent.doubleClick(segmentText);
    fireEvent.keyDown(segmentText, { key: 'Enter' });
    fireEvent.keyDown(segmentText, { key: ' ' });
    expect(audio.currentTime).toBe(atModeStart);
    expect(play).not.toHaveBeenCalled();
    expect(
      within(transcript).queryByRole('button', { name: 'Thanks for joining us today.' }),
    ).not.toBeInTheDocument();

    // Cancel brings them back.
    fireEvent.click(
      within(screen.getByRole('region', { name: 'Reassign speakers' })).getByRole('button', {
        name: 'Cancel',
      }),
    );
    expect(within(player).getByRole('button', { name: 'Play' })).toBeEnabled();
    expect(within(panel).getByRole('textbox', { name: 'Name of speaker 1' })).toBeEnabled();
    expect(within(panel).getByRole('button', { name: 'Merge speakers…' })).toBeEnabled();
  });

  it('asks before a move on a task with merges, and Cancel sends nothing', async () => {
    // Item 73's amendment (user decision 2026-10-31): a move makes the merges permanent, so the
    // view asks first. Mock addition: /turns reports merges until the move, then none (the move
    // baked them in) and segment 2 under speaker 0.
    const moved: Turns = {
      speakers: turnsFixture.speakers,
      hasMerges: false,
      turns: [
        {
          speakerId: 0,
          startMs: 0,
          endMs: 5200,
          text: 'Good morning. Thanks for joining us today.',
          segments: [
            ...turnsFixture.turns[0]!.segments,
            {
              id: 2,
              startMs: 2400,
              endMs: 5200,
              text: 'Thanks for joining us today.',
              edited: false,
            },
          ],
        },
        turnsFixture.turns[1]!,
        turnsFixture.turns[2]!,
      ],
    };
    let isMoved = false;
    const posted: unknown[] = [];
    server.use(
      http.get('/api/tasks/:id/turns', () =>
        HttpResponse.json(isMoved ? moved : { ...turnsFixture, hasMerges: true }),
      ),
      http.post('/api/tasks/:id/speakers/reassign', async ({ request }) => {
        posted.push(await request.json());
        isMoved = true;
        return HttpResponse.json({ speakers: moved.speakers });
      }),
    );
    await renderTranscript('3');
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    const panel = screen.getByRole('region', { name: 'Speakers' });
    expect(
      await within(panel).findByRole('button', { name: 'Reset all merges' }),
    ).toBeInTheDocument();
    fireEvent.click(within(panel).getByRole('button', { name: 'Reassign speakers' }));
    const bar = screen.getByRole('region', { name: 'Reassign speakers' });
    fireEvent.click(
      within(transcript).getByRole('checkbox', { name: 'Select segment starting 00:02' }),
    );
    fireEvent.click(within(bar).getByRole('button', { name: 'Move to…' }));
    fireEvent.change(within(bar).getByRole('combobox', { name: 'Move to speaker' }), {
      target: { value: '0' },
    });
    fireEvent.click(within(bar).getByRole('button', { name: 'Move' }));

    // The confirmation quotes the decision; Cancel sends nothing and keeps the selection.
    const dialog = screen.getByRole('dialog', { name: 'Reassign speakers?' });
    expect(dialog).toHaveTextContent(
      'This task has merged speakers. Reassigning makes those merges permanent: "Reset all merges" will no longer be available.',
    );
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(posted).toEqual([]);
    expect(bar).toHaveTextContent('1 segment selected');

    // Confirmed, the move is sent and the merges are gone afterwards.
    fireEvent.click(within(bar).getByRole('button', { name: 'Move' }));
    fireEvent.click(
      within(screen.getByRole('dialog', { name: 'Reassign speakers?' })).getByRole('button', {
        name: 'Move',
      }),
    );
    await waitFor(() =>
      expect(
        within(panel).queryByRole('button', { name: 'Reset all merges' }),
      ).not.toBeInTheDocument(),
    );
    expect(posted).toEqual([{ segmentIds: [2], speakerId: 0 }]);
  });

  it('re-reads /turns on a speakers-reassigned event, so a move made elsewhere shows at once', async () => {
    // Item 73's amendment (docs/api/speakers-reassigned.json shape). Mock addition: /turns serves
    // turns.json until another client's move, then segment 2 under a new speaker 2.
    const moved: Turns = {
      speakers: [...turnsFixture.speakers, { speakerId: 2, speaker: 'Speaker 3' }],
      hasMerges: false,
      turns: [
        {
          speakerId: 0,
          startMs: 0,
          endMs: 2100,
          text: 'Good morning.',
          segments: [{ id: 1, startMs: 0, endMs: 2100, text: 'Good morning.', edited: false }],
        },
        {
          speakerId: 2,
          startMs: 2400,
          endMs: 5200,
          text: 'Thanks for joining us today.',
          segments: [
            {
              id: 2,
              startMs: 2400,
              endMs: 5200,
              text: 'Thanks for joining us today.',
              edited: false,
            },
          ],
        },
        turnsFixture.turns[1]!,
        turnsFixture.turns[2]!,
      ],
    };
    let isMoved = false;
    const turnsRequests = countTurnsRequests(() => (isMoved ? moved : turnsFixture));
    const events = controllableEventStream();
    await renderTranscript('3');
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    expect((await within(transcript).findAllByText('Speaker 1')).length).toBe(2);
    expect(turnsRequests()).toBe(1);

    // Another client's move: the turns change along the moved segments, so the view reads /turns
    // again and shows the new speaker.
    isMoved = true;
    events.send('speakers-reassigned', { id: 3, speakers: moved.speakers });
    expect(await within(transcript).findByText('Speaker 3')).toBeInTheDocument();
    expect(turnsRequests()).toBe(2);
  });

  it('keeps exactly the ticked segments across a reload of the turns', async () => {
    // Item 73's amendment: the selection holds segment ids, so another client's change to the turns
    // cannot move it to a different segment. Mock addition: four turns after the merge of the
    // second and third.
    const reloaded: Turns = {
      speakers: turnsFixture.speakers,
      hasMerges: false,
      turns: [
        {
          speakerId: 0,
          startMs: 0,
          endMs: 2400,
          text: 'Good morning.',
          segments: [{ id: 1, startMs: 0, endMs: 2100, text: 'Good morning.', edited: false }],
        },
        {
          speakerId: 0,
          startMs: 2400,
          endMs: 5200,
          text: 'Thanks for joining us today.',
          segments: [
            {
              id: 2,
              startMs: 2400,
              endMs: 5200,
              text: 'Thanks for joining us today.',
              edited: false,
            },
          ],
        },
        turnsFixture.turns[1]!,
        turnsFixture.turns[2]!,
      ],
    };
    let isReloaded = false;
    server.use(
      http.get('/api/tasks/:id/turns', () =>
        HttpResponse.json(isReloaded ? reloaded : turnsFixture),
      ),
    );
    const events = controllableEventStream();
    await renderTranscript('3');
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    const panel = screen.getByRole('region', { name: 'Speakers' });
    fireEvent.click(await within(panel).findByRole('button', { name: 'Reassign speakers' }));
    const bar = screen.getByRole('region', { name: 'Reassign speakers' });
    fireEvent.click(
      within(transcript).getByRole('checkbox', { name: 'Select segment starting 00:02' }),
    );

    // The turns reload under the selection: segment 2 is still ticked, and nothing else is.
    isReloaded = true;
    events.send('speakers-merged', { id: 3, speakers: reloaded.speakers });
    await waitFor(() =>
      expect(
        within(transcript).getAllByRole('checkbox', { name: /^Select segment starting / }),
      ).toHaveLength(4),
    );
    const boxes = within(transcript).getAllByRole('checkbox', {
      name: /^Select segment starting /,
    });
    expect(boxes.map((box) => (box as HTMLInputElement).checked)).toEqual([
      false,
      true,
      false,
      false,
    ]);
    expect(bar).toHaveTextContent('1 segment selected');
  });
});

describe('times past one hour', () => {
  it('read as h:mm:ss like the player, in the turn gutter and on the status page', async () => {
    // A turn that starts 3661000 ms in = 1:01:01.
    const longTurns = {
      speakers: [{ speakerId: 0, speaker: 'Speaker 1' }],
      turns: [
        {
          speakerId: 0,
          startMs: 3661000,
          endMs: 3700000,
          segments: [{ id: 1, startMs: 3661000, endMs: 3700000, text: 'A long turn.' }],
        },
      ],
    };
    server.use(http.get('/api/tasks/3/turns', () => HttpResponse.json(longTurns)));
    await renderTranscript('3');
    expect(await screen.findByText('A long turn.')).toBeInTheDocument();
    expect(screen.getByText('1:01:01')).toBeInTheDocument();
    expect(screen.queryByText('61:01')).not.toBeInTheDocument();

    // The status page's "at" time reads the same way: transcribedUntilMs 3661000 → 1:01:01.
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          [
            'snapshot',
            {
              tasks: [{ ...taskFailed, id: 4, transcribedUntilMs: 3661000 }],
              queue: { paused: false, taskIds: [] },
            },
          ],
        ]),
      ),
    );
    await renderTranscript('4');
    expect(await screen.findByText(/failed while transcribing at 1:01:01/i)).toBeInTheDocument();
  });
});

describe('the reading column', () => {
  it("marks the transcript region with the task's exposed language", async () => {
    // Item 74: the API's task.language is whisper's detected language (the requested one as
    // fallback); the transcript carries it so fontconfig picks that script's glyph forms.
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          [
            'snapshot',
            {
              tasks: [{ ...taskCompleted, id: 3, language: 'ja' }],
              queue: { paused: false, taskIds: [] },
            },
          ],
        ]),
      ),
    );
    await renderTranscript('3');
    await screen.findByText('Good morning.');

    expect(screen.getByRole('region', { name: 'Transcript' })).toHaveAttribute('lang', 'ja');
  });

  it('never marks the transcript with the auto placeholder as its language', async () => {
    // Item 74 (discovered): an auto upload whose run detected nothing exposes the request's
    // placeholder "auto", which is not a language tag; the transcript then carries no lang.
    server.use(
      http.get('/api/events', () =>
        eventStreamResponse([
          [
            'snapshot',
            {
              tasks: [{ ...taskCompleted, id: 3, language: 'auto' }],
              queue: { paused: false, taskIds: [] },
            },
          ],
        ]),
      ),
    );
    await renderTranscript('3');
    await screen.findByText('Good morning.');

    expect(screen.getByRole('region', { name: 'Transcript' })).not.toHaveAttribute('lang');
  });

  it('is one centred 70ch column: header, player, downloads, then the turns with the start time in a left gutter and the speaker above the text', async () => {
    await renderTranscript('3'); // task 3 is COMPLETED; turns.json has three turns.
    await screen.findByText('Good morning.');

    // The whole page is one centred column at the ~70-character measure. jsdom computes
    // 70ch to 560px, so the token is asserted on the emitted rule.
    const column = required(screen.getByRole('main').firstElementChild) as HTMLElement;
    const columnRules = styleRules()
      .filter((rule): rule is CSSStyleRule => rule instanceof CSSStyleRule)
      .filter((rule) => column.matches(rule.selectorText));
    expect(columnRules.map((rule) => rule.style.maxWidth)).toContain('70ch');
    expect(getComputedStyle(column).marginLeft).toBe('auto');
    expect(getComputedStyle(column).marginRight).toBe('auto');

    // In order: the header block, the player, the downloads, then the turns.
    const parts = [
      screen.getByRole('heading', { level: 2, name: 'interview.mp3' }),
      screen.getByRole('group', { name: 'Audio player' }),
      screen.getByRole('link', { name: 'Download transcript (txt)' }),
      screen.getByRole('region', { name: 'Transcript' }),
    ];
    for (const [index, part] of parts.entries()) {
      expect(column).toContainElement(part);
      if (index > 0) {
        expect(
          parts[index - 1]!.compareDocumentPosition(part) & Node.DOCUMENT_POSITION_FOLLOWING,
        ).toBeTruthy();
      }
    }

    // Each turn is a row: its start time, muted, in a left gutter of one shared width, and
    // the speaker's name on a line above the turn's text.
    const region = screen.getByRole('region', { name: 'Transcript' });
    const start = within(region).getByText('00:00');
    expect(getComputedStyle(start).color).toBe(asRgb(lightTheme.colors.textMuted));
    const row = required(start.parentElement) as HTMLElement;
    expect(getComputedStyle(row).display).toBe('grid');
    const rows = [...required(row.parentElement).children] as HTMLElement[];
    expect(rows).toHaveLength(3);
    const gutters = rows.map((turn) => getComputedStyle(turn).gridTemplateColumns);
    expect(new Set(gutters).size).toBe(1);
    expect(gutters[0]).toMatch(/^\S+ 1fr$/);
    expect(gutters[0]).not.toMatch(/^(auto|max-content|min-content) /);
    const speaker = within(row).getByText('Speaker 1');
    expect(getComputedStyle(speaker).display).toBe('block');
    const text = required(within(row).getByText('Good morning.').closest('p'));
    expect(speaker.compareDocumentPosition(text) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    // The reading rhythm: the transcript line height, and the large gap between turns.
    expect(getComputedStyle(text).lineHeight).toBe(String(lightTheme.lineHeights.reading));
    expect(getComputedStyle(required(row.parentElement)).gap).toBe(lightTheme.spacing.lg);

    // On a phone the gutter folds: one column, the time above the speaker.
    const narrow = mediaRulesFor(row, `(max-width: ${lightTheme.breakpoints.sm})`);
    expect(narrow.map((rule) => rule.style.gridTemplateColumns)).toContain('1fr');
  });
});

// jsdom reports colours as rgb(); mirror the helper used by the other tests.
function asRgb(hex: string): string {
  const value = Number.parseInt(hex.slice(1), 16);
  return `rgb(${(value >> 16) & 255}, ${(value >> 8) & 255}, ${value & 255})`;
}

describe('a transcript opened after a merge', () => {
  // The connection's view of the merge, so the test can wait for the event before opening.
  function MergeArrived() {
    const merged = ConnectionContext.useSelector(
      (state) => state.context.speakerChanges[3]?.merged ?? false,
    );
    return <span>{merged ? 'merged' : 'pending'}</span>;
  }

  function OpenTranscript() {
    const navigate = useNavigate();
    return <button onClick={() => navigate('/history/3')}>open</button>;
  }

  it('fetches /turns once on opening: the stale merge is not replayed', async () => {
    const turnsRequests = countTurnsRequests();
    const events = controllableEventStream();
    render(
      <ThemedProviders>
        <ConnectionContext.Provider>
          <SettingsContext.Provider>
            <SettingsSync />
            <MemoryRouter initialEntries={['/history']}>
              <MergeArrived />
              <OpenTranscript />
              <Routes>
                <Route path="/history" element={<p>history</p>} />
                <Route path="/history/:taskId" element={<TaskTranscriptView />} />
              </Routes>
            </MemoryRouter>
          </SettingsContext.Provider>
        </ConnectionContext.Provider>
      </ThemedProviders>,
    );

    // The merge lands before the transcript opens; the probe confirms the connection holds it.
    await waitFor(() =>
      events.send('speakers-merged', { id: 3, speakers: [{ speakerId: 0, speaker: 'Alice' }] }),
    );
    await screen.findByText('merged');

    fireEvent.click(screen.getByRole('button', { name: 'open' }));
    expect(await screen.findByText('Good morning.')).toBeInTheDocument();

    // One read on opening; the stale merge was not replayed over it.
    expect(turnsRequests()).toBe(1);
    const transcript = screen.getByRole('region', { name: 'Transcript' });
    expect(within(transcript).getByText('Speaker 2')).toBeInTheDocument();
  });
});
