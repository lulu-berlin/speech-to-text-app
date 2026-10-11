import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi, type Mock } from 'vitest';
import { App } from '../../app/App';
import { lightTheme } from '../../shared/theme/theme';
import { eventStreamResponse } from '../../shared/api/handlers';
import {
  error as errorFixture,
  taskCanceled,
  taskCompleted,
  taskFailed,
  turns as turnsFixture,
} from '../../shared/api/fixtures';
import { controllableEventStream } from '../../test/eventStream';
import { mediaRulesFor, px, required, styleRules } from '../../test/dom';
import { server } from '../../test/server';

function renderTranscript(taskId: string) {
  render(
    <MemoryRouter initialEntries={[`/history/${taskId}`]}>
      <App />
    </MemoryRouter>,
  );
  return screen.findByText('Live');
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
    audio.currentTime = 1.0;
    fireEvent(audio, new Event('timeupdate'));
    expect(scrollIntoView).toHaveBeenCalledTimes(1);

    // A hand scroll stops following: a "Back to playing position" button appears. A wheel
    // over the region is a hand scroll (item 36); a bare scroll event is not the user
    // scrolling — the view's own follow scroll fires one too — so it never stops following.
    fireEvent.wheel(region);
    expect(backButton()).toBeInTheDocument();

    // While stopped, playback no longer scrolls.
    scrollIntoView.mockClear();
    audio.currentTime = 6.0;
    fireEvent(audio, new Event('timeupdate'));
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
    audio.currentTime = 1.0;
    fireEvent(audio, new Event('timeupdate'));
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
      audio.currentTime = cueSeconds;
      fireEvent(audio, new Event('timeupdate'));
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
    audio.currentTime = 1.0;
    fireEvent(audio, new Event('timeupdate'));
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    audio.currentTime = 6.0;
    fireEvent(audio, new Event('timeupdate'));

    fireEvent.wheel(region);
    expect(backButton()).toBeInTheDocument();

    // Stopped stays stopped: the advancing playhead no longer triggers follow scrolls.
    scrollIntoView.mockClear();
    audio.currentTime = 10.0;
    fireEvent(audio, new Event('timeupdate'));
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
    audio.currentTime = 1.0;
    fireEvent(audio, new Event('timeupdate'));
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
    audio.currentTime = 6.0;
    fireEvent(audio, new Event('timeupdate'));
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('resumes following from "Back to playing position" and scrolls to the playing segment', async () => {
    const scrollIntoView = stubScrollIntoView();
    await renderTranscript('3'); // task 3 is COMPLETED (id 3, fixed speakers).
    const audio = screen.getByLabelText('Audio for interview.mp3') as HTMLAudioElement;
    const region = screen.getByRole('region', { name: /transcript/i });

    // Play, then a wheel stops following.
    audio.currentTime = 1.0;
    fireEvent(audio, new Event('timeupdate'));
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    fireEvent.wheel(region);
    expect(backButton()).toBeInTheDocument();

    // While stopped, the playhead keeps moving — the highlight follows the playhead even
    // when the view is not scrolling — so the playing segment on resume is not the old one.
    audio.currentTime = 6.0;
    fireEvent(audio, new Event('timeupdate'));

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
    // reserves the compact header's height (the compact mascot plus the border, all
    // tokens) and keeps it clear of the sticky header (item 31).
    audio.currentTime = 1.0;
    fireEvent(audio, new Event('timeupdate'));
    const active = required(document.querySelector('[data-active]'));
    expect(getComputedStyle(active).scrollMarginTop).toBe(
      `${
        Number.parseFloat(lightTheme.sizes.stenoCompact) * 16 +
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

    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    let dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('call.ogg');

    // Cancel sends nothing and keeps the page.
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(deleted).toEqual([]);

    // Confirming sends DELETE /api/tasks/4 and lands back on the history list.
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
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

    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
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
    let turnsRequests = 0;
    server.use(
      http.get('/api/tasks/:id/turns', () => {
        turnsRequests += 1;
        return HttpResponse.json(isMerged ? merged : turnsFixture);
      }),
    );
    const events = controllableEventStream();
    await renderTranscript('3'); // task 3 is COMPLETED; turns.json has speakers 0 and 1.
    const transcript = await screen.findByRole('region', { name: 'Transcript' });
    const panel = screen.getByRole('region', { name: 'Speakers' });
    expect(turnsRequests).toBe(1);

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
    expect(turnsRequests).toBe(1);

    // Another task's event changes nothing here.
    events.send('speakers-renamed', { id: 5, speakers: [{ speakerId: 0, speaker: 'Zoe' }] });
    expect(within(transcript).queryByText('Zoe')).not.toBeInTheDocument();

    // A merge elsewhere: turns can join, so the view reads /turns again.
    isMerged = true;
    events.send('speakers-merged', { id: 3, speakers: merged.speakers });
    await waitFor(() =>
      expect(within(transcript).queryByText('Speaker 2')).not.toBeInTheDocument(),
    );
    expect(turnsRequests).toBe(2);
    expect(within(transcript).getAllByText('Alice')).toHaveLength(1);
    expect(within(panel).getByRole('button', { name: 'Reset all merges' })).toBeInTheDocument();
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
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.queryByText('Good morning.')).not.toBeInTheDocument();

    // The turns arrive and take its place.
    release();
    expect(await screen.findByText('Good morning.')).toBeInTheDocument();
    expect(screen.queryByText('Loading transcript…')).not.toBeInTheDocument();
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
    audio.currentTime = 6.0; // 6000 ms, inside turn 2 [5800,9100)
    fireEvent(audio, new Event('timeupdate'));
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
    audio.currentTime = 6.0; // 6000 ms, inside turn 2 [5800,9100)
    fireEvent(audio, new Event('timeupdate'));
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
      audio.currentTime = 6.4;
      fireEvent(audio, new Event('timeupdate'));
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
          segments: [{ startMs: 3661000, endMs: 3700000, text: 'A long turn.' }],
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
