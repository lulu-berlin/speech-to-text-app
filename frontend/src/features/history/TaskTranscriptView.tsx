import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { MoreVertical } from 'lucide-react';
import { useMachine } from '@xstate/react';
import { useNavigate, useParams } from 'react-router';
import styled, { useTheme } from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import { editSegment, revertSegment } from '../../shared/api/client';
import { errorMessage } from '../../shared/api/types';
import type { Segment, Task, TaskPhase, Turns } from '../../shared/api/types';
import { Page, Row, Stack } from '../../shared/ui/layout';
import { MetaText, PageHeading, Text } from '../../shared/ui/typography';
import { Button } from '../../shared/ui/Button';
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog';
import { focusRing } from '../../shared/ui/focusRing';
import { DropdownMenu } from '../../shared/ui/DropdownMenu';
import type { DropdownMenuItem } from '../../shared/ui/DropdownMenu';
import { IconButton } from '../../shared/ui/IconButton';
import { LoadingState } from '../../shared/ui/LoadingState';
import { ErrorCallout } from '../../shared/ui/ErrorCallout';
import { TextLink } from '../../shared/ui/TextLink';
import { turnsMachine } from './turnsMachine';
import { currentSegmentAt } from './segmentTiming';
import { commandMachine } from '../../shared/state/commandMachine';
import { confirmActionMachine } from '../../shared/state/confirmActionMachine';
import { formatDuration } from '../../shared/formatDuration';
import { formatTaskMeta } from './formatTask';
import { TranscriptTurns } from './TranscriptTurns';
import { AudioPlayer, playheadMs } from './AudioPlayer';
import { playbackMachine } from './playbackMachine';
import { followModeMachine } from './followModeMachine';
import { SpeakersPanel } from './SpeakersPanel';

// The plain-word form of a running phase, for "Failed/Canceled while <phase>".
const PHASE_WORDS: Record<Exclude<TaskPhase, null>, string> = {
  CHUNKING: 'chunking',
  TRANSCRIBING: 'transcribing',
  DIARIZING: 'diarizing',
};

function phaseWord(phase: TaskPhase): string {
  return phase === null ? 'processing' : PHASE_WORDS[phase];
}

// The statuses that get a status page instead of the transcript.
const FINISHED_ISSUES: ReadonlySet<Task['status']> = new Set(['FAILED', 'CANCELED']);

// The statuses that are still queued or running: no transcript, no status page yet.
const PENDING: ReadonlySet<Task['status']> = new Set(['QUEUED', 'RUNNING', 'PAUSED']);

// The transcript download formats (item 10, ADR 0035): txt, srt, vtt.
const DOWNLOAD_FORMATS = ['txt', 'srt', 'vtt'] as const;

// What a click must land on to act as itself (item 52): a segment and its controls are
// buttons, and so are the player's controls, the download links and the speakers panel's
// fields. Anything else is "outside" — it stops playback and lands on the current segment.
const CONTROLS =
  'button, a, input, textarea, select, label, [role="button"], [role="link"], [role="slider"], [contenteditable="true"]';

// The transcript's segments: the button-role elements without an aria-label (the current
// segment's controls carry one), in reading order (item 52).
const SEGMENTS = '[role="button"]:not([aria-label])';

// The arrow keys' step between segments (item 52): the previous segment, or the next.
const ARROW_STEP: Record<string, number> = {
  ArrowLeft: -1,
  ArrowUp: -1,
  ArrowRight: 1,
  ArrowDown: 1,
};

// The scroll keys (item 36): the keys a browser turns into scrolling for the focused
// scroll container — the transcript region, when focus is on one of its segments.
const SCROLL_KEYS: Record<string, true> = {
  PageUp: true,
  PageDown: true,
  ' ': true,
  ArrowUp: true,
  ArrowDown: true,
  ArrowLeft: true,
  ArrowRight: true,
};

const DownloadLink = styled.a`
  color: ${(props) => props.theme.colors.accent};
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }

  ${focusRing}
`;

const Title = styled(PageHeading)`
  margin: 0;
`;

const NoAudioNote = styled(Text)`
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

const TranscriptRegion = styled.div`
  max-height: ${(props) => props.theme.sizes.transcriptMaxHeight};
  overflow-y: auto;
`;

// The status page's Retry: runs through commandMachine (the one command mechanism, item
// 28), so on done the returned task updates the store and the view returns to the queue.
// Its own component because the machine input needs the loaded task's status, which the
// parent does not have before the first snapshot (useMachine fixes the input at mount).
function RetryButton({ task }: { task: Task }) {
  const connection = ConnectionContext.useActorRef();
  const navigate = useNavigate();
  const [retryState, retrySend, retryActor] = useMachine(commandMachine, {
    input: { command: 'retry', taskId: task.id, status: task.status },
  });

  useEffect(() => {
    if (retryState.matches('done') && retryState.context.task !== null) {
      connection.send({ type: 'task-updated', task: retryState.context.task });
      navigate('/queue');
    }
  }, [retryState, connection, navigate]);

  return (
    <Button
      type="button"
      onClick={() => {
        if (retryState.matches('failed') || retryState.matches('done')) {
          retryActor.stop();
          retryActor.start();
        }
        retrySend({ type: 'START' });
      }}
    >
      Retry
    </Button>
  );
}

export function TaskTranscriptView() {
  const { taskId } = useParams();
  const id = Number(taskId);
  const navigate = useNavigate();
  const tasks = ConnectionContext.useSelector((state) => state.context.tasks);
  const connection = ConnectionContext.useSelector((state) => state.value);
  const task = tasks.find((candidate) => candidate.id === id);
  const completed = task?.status === 'COMPLETED';

  // Delete from the detail view (status page and transcript), always confirmed (decision B,
  // 2026-10-10): the flow lives in confirmActionMachine; on success the view returns to the
  // history list. The same machine serves both branches via one shared dialog.
  const [deleteState, deleteSend, deleteActor] = useMachine(confirmActionMachine, {
    input: { taskId: id },
  });
  useEffect(() => {
    if (deleteState.matches('done')) {
      navigate('/history');
    }
  }, [deleteState, navigate]);

  // Delete leaves the reading row (item 59): it lives behind the transcript's actions menu
  // and still runs through the shared confirmation. A finished or failed dialog machine is
  // restarted first, as the row and history flows do.
  const requestDelete = () => {
    if (deleteState.matches('failed') || deleteState.matches('done')) {
      deleteActor.stop();
      deleteActor.start();
    }
    deleteSend({ type: 'REQUEST' });
  };
  const transcriptMenuItems: DropdownMenuItem[] = [
    { label: 'Delete', danger: true, onSelect: requestDelete },
  ];

  // The turns of a completed task load through turnsMachine (only a completed task has
  // turns; the backend answers 409 not-completed otherwise): once per task, again when the
  // view moves to another task, and on Retry after a failure.
  const [turnsState, turnsSend] = useMachine(turnsMachine, { input: { taskId: id } });
  useEffect(() => {
    if (completed && (turnsState.matches('idle') || turnsState.context.taskId !== id)) {
      turnsSend({ type: 'LOAD', taskId: id });
    }
  }, [completed, id, turnsState, turnsSend]);
  const turns = turnsState.context.turns;

  // Item 22: the latest speakers event of this task. A rename brings the names (the turns
  // stay as they are); a merge or a reset can join or split turns, so /turns is read again.
  const speakerChange = ConnectionContext.useSelector((state) => state.context.speakerChanges[id]);
  useEffect(() => {
    if (speakerChange === undefined) {
      return;
    }
    if (!speakerChange.merged) {
      turnsSend({ type: 'SPEAKERS', speakers: speakerChange.speakers });
      return;
    }
    // A failed reload lands on the turns error callout, like the initial load.
    turnsSend({ type: 'RELOAD' });
  }, [id, speakerChange, turnsSend]);

  // Item 52: the latest edit of each edited segment of this task replaces that segment in
  // place; a text edit changes no turn boundary, so /turns is not read again. The edits are
  // applied again whenever the turns have (re)loaded: a /turns answer read before an edit was
  // stored still holds the old text.
  const segmentEdits = ConnectionContext.useSelector((state) => state.context.segmentEdits[id]);
  const turnsLoaded = turnsState.matches('loaded');
  useEffect(() => {
    if (segmentEdits !== undefined && turnsLoaded) {
      turnsSend({ type: 'SEGMENTS', segments: Object.values(segmentEdits) });
    }
  }, [segmentEdits, turnsLoaded, turnsSend]);

  const theme = useTheme();
  const tints = theme.colors.speakerTints;
  const turnsBySpeaker = useMemo(() => {
    if (turns === null) {
      return null;
    }
    // For speakers off, the turns carry speakerId null and the speakers list is empty, so
    // the lookup yields undefined and no name (nor tint) is rendered.
    // By speakerId, not list position: after a merge the effective speakers skip merged ids.
    return turns.turns.map((turn) => ({
      turn,
      speaker: turns.speakers.find((speaker) => speaker.speakerId === turn.speakerId),
    }));
  }, [turns]);

  // The playhead on the /audio timeline in milliseconds (item 8): updated from the audio
  // element's timeupdate and seeked events; drives the playback highlight.
  const [playback, playbackSend] = useMachine(playbackMachine);
  const currentTimeMs = playback.context.currentTimeMs;
  const playing = playback.matches('playing');
  const audioRef = useRef<HTMLAudioElement>(null);

  // All segments in time order, so the highlight can be a binary search over the starts.
  const segmentsInOrder = useMemo(
    () => (turns === null ? [] : turns.turns.map((turn) => turn.segments).flat()),
    [turns],
  );
  // The leading silence is unplayable (item 52); both the seek and the play path use this.
  const firstSegmentStartMs = segmentsInOrder[0]?.startMs;

  // Moves the audio and the playhead to `ms`: the player's seek bar (on release) and a click
  // on a segment (item 52: it makes the segment current; playing goes on if it was playing).
  // The machine's SEEK also ends any drag draft. A seek also ends a segment played on its own
  // (item 52, confirmed 2026-10-23): playback goes on from there as an ordinary play.
  const seek = (ms: number) => {
    const audio = audioRef.current;
    if (audio === null) {
      return;
    }
    // The silence before the first segment is unplayable (item 52, discovered): a seek into it
    // lands on the first segment's start, so a current segment always exists.
    const target =
      firstSegmentStartMs !== undefined && ms < firstSegmentStartMs ? firstSegmentStartMs : ms;
    setWindowSegment(null);
    playbackSend({ type: 'SEEK', ms: target });
    audio.currentTime = target / 1000;
  };

  // A double-click or Enter/Space on a segment plays from its start (item 9, moved off the
  // single click by item 52).
  const playFrom = (startMs: number) => {
    seek(startMs);
    audioRef.current?.play().catch(() => {
      /* A browser may refuse playback before any user gesture (autoplay policy); the seek
         itself still landed, so the highlight follows. */
    });
  };

  // Where a resume must start (item 52): after the media's end, play() rewinds to 0, so the
  // current segment restarts; in the leading silence, the first segment does.
  const settleResumePosition = (audio: HTMLAudioElement) => {
    if (audio.ended && currentSegment !== null) {
      seek(currentSegment.startMs);
    }
    if (firstSegmentStartMs !== undefined && playheadMs(audio) < firstSegmentStartMs) {
      seek(firstSegmentStartMs);
    }
  };

  // A Stop (the user's Pause/Stop model, item 52): playback ends at the segment's start, which
  // stays current, so playing again starts it over; the seek ends a segment played on its own.
  const stopAt = (segment: Segment) => {
    audioRef.current?.pause();
    seek(segment.startMs);
    setChosenSegmentId(segment.id);
  };

  // Escape and a click on no control: a Stop at the current segment — while a segment plays on
  // its own, that segment.
  const stopPlaying = () => {
    if (!playing) {
      return;
    }
    const segment = windowSegment ?? currentSegment;
    if (segment === null) {
      audioRef.current?.pause();
      return;
    }
    stopAt(segment);
  };

  // Pauses where it is, or resumes from where it paused — not from the segment's start:
  // Enter/Space on the current segment (item 52), and the player's Play/Pause button. A segment
  // played on its own stays so through the pause, and still stops at its end.
  const togglePlayback = () => {
    const audio = audioRef.current;
    if (audio === null) {
      return;
    }
    if (playing) {
      audio.pause();
    } else {
      settleResumePosition(audio);
      audio.play().catch(() => {
        /* Refused before any user gesture (autoplay policy); nothing to undo. */
      });
    }
  };

  // The segment the user chose last (click, double-click, Enter/Space): at its own instant it is
  // the current segment even when the next one starts there too — whisper can emit such a
  // duplicate (item 52, user report 2026-10-23). A zero-length segment counts as containing its
  // own instant; otherwise the playhead's segment wins.
  const [chosenSegmentId, setChosenSegmentId] = useState<number | null>(null);
  // The segment whose inline editor is open (item 52), or null, and a rejected save's detail
  // shown in it.
  const [editingSegmentId, setEditingSegmentId] = useState<number | null>(null);
  const [editError, setEditError] = useState<string | null>(null);
  // The segment an isolated play is playing (item 52), or null: it stops at the segment's end.
  const [windowSegment, setWindowSegment] = useState<Segment | null>(null);
  const chosenSegment =
    chosenSegmentId === null
      ? null
      : (segmentsInOrder.find((segment) => segment.id === chosenSegmentId) ?? null);
  const chosenHolds =
    chosenSegment !== null &&
    currentTimeMs >= chosenSegment.startMs &&
    (chosenSegment.startMs === chosenSegment.endMs
      ? currentTimeMs === chosenSegment.startMs
      : currentTimeMs < chosenSegment.endMs);
  const currentSegment = chosenHolds
    ? chosenSegment
    : currentSegmentAt(segmentsInOrder, currentTimeMs);

  // Follow mode (item 10): while the playhead moves and the user has not scrolled by hand,
  // the current segment is kept in view. A hand scroll stops following; "Back to playing
  // position" resumes it.
  const [followMode, followModeSend] = useMachine(followModeMachine);
  const following = followMode.matches('following');
  const transcriptRegionRef = useRef<HTMLDivElement>(null);
  // The rendered element of the current segment — the one the highlight marks. Follow mode
  // scrolls to it, focus rides to it, and Escape lands on it (item 52).
  const currentSegmentElement = (): HTMLElement | null =>
    transcriptRegionRef.current?.querySelector<HTMLElement>('[data-active]') ?? null;
  // Where the region stood when a pointer press started: a press that ends with the region
  // scrolled is a drag on its scrollbar (item 36); a plain press or a selection that does
  // not scroll leaves following alone.
  const dragStartScrollRef = useRef<{ top: number; left: number } | null>(null);

  useEffect(() => {
    if (!following || currentSegment === null) {
      return;
    }
    // The current segment is where the highlight is; scroll it into the reader's sight.
    currentSegmentElement()?.scrollIntoView({ block: 'nearest' });
  }, [following, currentSegment]);

  // Focus rides along with the current segment, so a repeated Space acts on what is playing
  // (item 52, the user's keyboard trial) — only when it was on the previous current segment:
  // focus the user put elsewhere stays there. Its move must not scroll; follow mode owns
  // scrolling. Searched by the rendered mark so it needs no ref per segment.
  const previousElementRef = useRef<HTMLElement | null>(null);
  useEffect(() => {
    const previous = previousElementRef.current;
    const element = currentSegmentElement();
    previousElementRef.current = element;
    const active = document.activeElement;
    if (
      previous === null ||
      active === null ||
      !(active === previous || previous.contains(active))
    ) {
      return;
    }
    if (element instanceof HTMLElement) {
      element.focus({ preventScroll: true });
    }
  }, [currentSegment]);

  // An edit's or a revert's answer (item 52, ADR 0061): the stored segment is applied in place
  // (no /turns refetch) and the editor closes; a rejection keeps the draft open and shows the
  // server's detail.
  const settleEdit = (request: Promise<Turns>, segmentId: number) => {
    void request
      .then((next) => {
        const stored = next.turns
          .flatMap((turn) => turn.segments)
          .find((candidate) => candidate.id === segmentId);
        if (stored !== undefined) {
          turnsSend({ type: 'SEGMENTS', segments: [stored] });
        }
        setEditingSegmentId(null);
        setEditError(null);
      })
      .catch((error: unknown) => {
        setEditError(errorMessage(error));
      });
  };

  // Opening a segment's editor (item 52): the segment becomes current and the dialog opens. The
  // Edit control, Enter on it and the segment's own `e` all land here.
  const openEditor = (segment: Segment) => {
    setChosenSegmentId(segment.id);
    setEditError(null);
    setEditingSegmentId(segment.id);
  };

  // Saving an edit (item 52): the client trims the text before it is stored.
  const saveEdit = (segment: Segment, text: string) => {
    settleEdit(editSegment(id, segment.id, text.trim()), segment.id);
  };

  // Isolated segment playback (item 52): play exactly [startMs, endMs) and stop at its end,
  // without running on into the next segment.
  const playSegmentWindow = (segment: Segment) => {
    seek(segment.startMs);
    setWindowSegment(segment);
    audioRef.current?.play().catch(() => {
      /* Refused before any user gesture (autoplay policy); the seek itself landed. */
    });
  };

  // Reverting an edit (ADR 0061): whisper's text comes back through the same answer path.
  const revertEdit = (segment: Segment) => {
    settleEdit(revertSegment(id, segment.id), segment.id);
  };

  // The player's Play/Pause does not keep focus: it goes to the current segment — before the
  // playhead has reached one, to the first — so Enter/Space toggles there and Tab continues
  // from it (item 52, the user's keyboard trial).
  const toggleFromButton = () => {
    togglePlayback();
    const target =
      currentSegmentElement() ?? transcriptRegionRef.current?.querySelector<HTMLElement>(SEGMENTS);
    target?.focus({ preventScroll: true });
  };

  // A click that lands on no control is "outside" (item 52): it removes the staging focus and
  // stops playback, landing on the current segment, which keeps its highlight. The player's own
  // controls, the downloads and the speakers panel are controls and act as themselves.
  useEffect(() => {
    const handleClick = (event: MouseEvent) => {
      const target = event.target;
      if (!(target instanceof Element) || target.closest(CONTROLS) !== null) {
        return;
      }
      // An open editor keeps its draft and its focus through any click (item 52, the user's
      // decision): only Discard or Escape close it.
      if (editingSegmentId !== null) {
        return;
      }
      stopPlaying();
      currentSegmentElement()?.focus({ preventScroll: true });
    };
    document.addEventListener('click', handleClick);
    return () => document.removeEventListener('click', handleClick);
  }, [playback, editingSegmentId, windowSegment, currentSegment]);

  // A staging focus — focus on a segment that is not current, by Tab or an arrow key — is a
  // scroll away by hand: following stops (item 52, the user's trial). The view's own focus
  // moves all land on the current segment, so they never stop it. A native `focusin` listener:
  // Tab moves focus outside any React handler, and the event bubbles from the segment.
  useEffect(() => {
    const region = transcriptRegionRef.current;
    if (region === null) {
      return;
    }
    const handleFocusIn = (event: FocusEvent) => {
      const segment = event.target instanceof Element ? event.target.closest(SEGMENTS) : null;
      if (segment !== null && segment !== currentSegmentElement()) {
        sendScrolled();
      }
    };
    region.addEventListener('focusin', handleFocusIn);
    return () => region.removeEventListener('focusin', handleFocusIn);
  });

  // A hand scroll is input that scrolls the region (item 36, decided 2026-10-11): wheel,
  // touch, the scroll keys, or a scrollbar drag. A bare scroll event is never input
  // evidence — the view's own follow scroll fires one too, so listening for it would stop
  // following on its own (the hold-off race). Input detection needs no hold-off timer.
  const sendScrolled = () => {
    followModeSend({ type: 'SCROLLED' });
  };

  // The scroll keys' default is the browser scrolling the focused scroll container. A key
  // press whose default is already handled (Enter/Space on a segment button, which play
  // instead of scrolling) is not scroll input.
  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.defaultPrevented) {
      return;
    }
    // The editor's dialog renders inside this region's React tree (its panel is portalled out),
    // so its keys still bubble here: they belong to the dialog, not to the transcript's scroll
    // keys or shortcuts (root review 2026-10-23).
    if (event.target instanceof Element && event.target.closest('[role="dialog"]') !== null) {
      return;
    }
    // The focused segment answers two bare keys (root review 2026-10-23): `e` opens its editor,
    // `p` plays it on its own — and stops it again, as its own control does. The segments render
    // in time order, so the focused one is the same index in the ordered list.
    const onSegment =
      event.target instanceof Element ? event.target.closest<HTMLElement>(SEGMENTS) : null;
    if (onSegment !== null && !event.altKey && !event.ctrlKey && !event.metaKey) {
      const focused = [
        ...(transcriptRegionRef.current?.querySelectorAll<HTMLElement>(SEGMENTS) ?? []),
      ].indexOf(onSegment);
      const segment = segmentsInOrder[focused] ?? null;
      if (segment !== null && event.key.toLowerCase() === 'e') {
        event.preventDefault();
        openEditor(segment);
        return;
      }
      if (segment !== null && event.key.toLowerCase() === 'p') {
        event.preventDefault();
        if (playing && windowSegment?.id === segment.id) {
          stopPlaying();
        } else {
          playSegmentWindow(segment);
          setChosenSegmentId(segment.id);
        }
        return;
      }
    }
    // Escape (item 52): stop what is playing and land on the current segment — the one the
    // highlight marks, not a segment that only holds the staging focus — so Space resumes
    // from it; the highlight stays.
    if (event.key === 'Escape') {
      event.preventDefault();
      stopPlaying();
      currentSegmentElement()?.focus({ preventScroll: true });
      return;
    }
    // The arrow keys with focus on a segment step the staging focus to the previous (Left/Up)
    // or next (Right/Down) segment, skipping the controls, and are not a hand scroll; with
    // focus anywhere else they scroll the transcript as before (item 52).
    const step = ARROW_STEP[event.key];
    if (onSegment !== null && step !== undefined) {
      event.preventDefault();
      const segments = [
        ...(transcriptRegionRef.current?.querySelectorAll<HTMLElement>(SEGMENTS) ?? []),
      ];
      segments[segments.indexOf(onSegment) + step]?.focus();
      return;
    }
    if (SCROLL_KEYS[event.key] === true) {
      sendScrolled();
    }
  };

  // A scrollbar drag scrolls the region while the button is held, so a pointer move that
  // finds a changed scroll position means the user dragged it (or a selection drag
  // scrolled) — following stops then, not on the press itself, which may be a click.
  const handlePointerDown = () => {
    const region = transcriptRegionRef.current;
    dragStartScrollRef.current =
      region === null ? null : { top: region.scrollTop, left: region.scrollLeft };
  };

  const handlePointerMove = () => {
    const start = dragStartScrollRef.current;
    const region = transcriptRegionRef.current;
    if (start === null || region === null) {
      return;
    }
    if (region.scrollTop !== start.top || region.scrollLeft !== start.left) {
      dragStartScrollRef.current = null;
      sendScrolled();
    }
  };

  const handlePointerEnd = () => {
    dragStartScrollRef.current = null;
  };

  // Until the first snapshot lands, "no such task" just means the store has not loaded yet,
  // and the shell's connecting region is the loading state, so the view renders nothing of
  // its own (item 25); only once the connection is live is an absent id a missing task
  // (deleted or never made).
  if (task === undefined) {
    if (connection !== 'live') {
      return null;
    }
    return (
      <Page measure="reading">
        <Stack gap="md">
          <Title>Task not found</Title>
          <ErrorCallout
            title="This task doesn't exist"
            detail="It was deleted, or the link is wrong."
            action={<TextLink to="/history">Back to History</TextLink>}
          />
        </Stack>
      </Page>
    );
  }

  // The shared delete confirmation, opened by the Delete button in either branch. The
  // dialog's open/closed lives in confirmActionMachine (confirming/running), not React state.
  const deleteDialog = (
    <ConfirmDialog
      open={deleteState.matches('confirming') || deleteState.matches('running')}
      title="Delete task?"
      confirmLabel="Delete"
      onConfirm={() => deleteSend({ type: 'CONFIRM' })}
      onCancel={() => deleteSend({ type: 'CANCEL' })}
    >
      {task.filename} and its transcript and audio will be deleted.
    </ConfirmDialog>
  );

  // A task still in the queue (or running) has no transcript: say so and link to the queue.
  if (PENDING.has(task.status)) {
    return (
      <Page measure="reading">
        <Stack gap="md">
          <Title>{task.filename}</Title>
          <Text>This task is still in the queue.</Text>
          <TextLink to="/queue">Go to the queue</TextLink>
        </Stack>
      </Page>
    );
  }

  // The audio block is shared: the themed player when the task has audio, otherwise the
  // note.
  const audioBlock = task.hasAudio ? (
    <AudioPlayer
      src={`/api/tasks/${id}/audio`}
      filename={task.filename}
      durationMs={task.durationMs ?? 0}
      currentTimeMs={currentTimeMs}
      dragMs={playback.context.dragMs}
      playing={playing}
      audioRef={audioRef}
      onPlayhead={(ms) => {
        // At its end a segment played on its own stops — the next segment never becomes
        // current, however late the time update lands.
        if (windowSegment !== null && ms >= windowSegment.endMs) {
          stopAt(windowSegment);
          return;
        }
        playbackSend({ type: 'PLAYHEAD', ms });
      }}
      onSeek={seek}
      onDrag={(ms) => playbackSend({ type: 'DRAG', ms })}
      onPlayingChange={(playing) => playbackSend({ type: playing ? 'PLAYING' : 'IDLE' })}
      onToggle={toggleFromButton}
      disabled={editingSegmentId !== null}
    />
  ) : (
    <NoAudioNote>This task has no audio because it ended before chunking.</NoAudioNote>
  );

  // A failed or canceled task gets a status page, not a transcript (item 15).
  if (FINISHED_ISSUES.has(task.status)) {
    const outcome = task.status === 'FAILED' ? 'Failed' : 'Canceled';
    const progress =
      task.transcribedUntilMs !== null ? ` at ${formatDuration(task.transcribedUntilMs)}` : '';
    return (
      <Page measure="reading">
        <Stack gap="md">
          <Title>{task.filename}</Title>
          <Text>
            {outcome} while {phaseWord(task.phase)}
            {progress}
          </Text>
          {task.error !== null ? <Text>Error: {task.error}</Text> : null}
          {audioBlock}
          <Row gap="sm">
            <RetryButton task={task} />
            <DropdownMenu
              trigger={<IconButton icon={MoreVertical} label={`Actions for ${task.filename}`} />}
              align="end"
              items={transcriptMenuItems}
            />
          </Row>
          {deleteState.matches('failed') ? (
            <Text>Delete failed: {errorMessage(deleteState.context.error)}</Text>
          ) : null}
        </Stack>
        {deleteDialog}
      </Page>
    );
  }

  return (
    <Page measure="reading">
      <Stack gap="md">
        <Stack gap="xs">
          <Title>{task.filename}</Title>
          <MetaText>{formatTaskMeta(task)}</MetaText>
        </Stack>
        {audioBlock}
        <Row gap="sm">
          {DOWNLOAD_FORMATS.map((format) => (
            <DownloadLink
              key={format}
              href={`/api/tasks/${id}/transcript?format=${format}`}
              aria-label={`Download transcript (${format})`}
            >
              {format.toUpperCase()}
            </DownloadLink>
          ))}
          <DropdownMenu
            trigger={<IconButton icon={MoreVertical} label={`Actions for ${task.filename}`} />}
            align="end"
            items={transcriptMenuItems}
          />
        </Row>
        {turns !== null && turns.speakers.length > 0 ? (
          // Item 22: the panel edits the effective speakers; a stored change replaces the
          // speakers the turns are labelled with (turns reference them by speakerId).
          <SpeakersPanel
            taskId={id}
            speakers={turns.speakers}
            hasMerges={turns.hasMerges}
            onSpeakersChanged={(speakers) => turnsSend({ type: 'SPEAKERS', speakers })}
            // A merge or a reset joins or splits turns, so the turns are read again; a
            // failed reload shows the turns error callout, like the initial load.
            onMergesChanged={() => turnsSend({ type: 'RELOAD' })}
          />
        ) : null}
        {turnsState.matches('failed') ? (
          <ErrorCallout
            title="Couldn't load the transcript"
            detail={errorMessage(turnsState.context.error)}
            action={
              <Button
                type="button"
                variant="secondary"
                onClick={() => turnsSend({ type: 'RETRY' })}
              >
                Retry
              </Button>
            }
          />
        ) : turnsBySpeaker === null ? (
          <LoadingState label="Loading transcript…" />
        ) : (
          <TranscriptRegion
            ref={transcriptRegionRef}
            role="region"
            aria-label="Transcript"
            onWheel={sendScrolled}
            onTouchMove={sendScrolled}
            onKeyDown={handleKeyDown}
            onPointerDown={handlePointerDown}
            onPointerMove={handlePointerMove}
            onPointerUp={handlePointerEnd}
            onPointerCancel={handlePointerEnd}
          >
            <TranscriptTurns
              turnsBySpeaker={turnsBySpeaker}
              currentSegment={currentSegment}
              playing={playing}
              playingOnItsOwnId={playing ? (windowSegment?.id ?? null) : null}
              onStopSegment={stopPlaying}
              onSegmentSeek={(segment) => {
                seek(segment.startMs);
                setChosenSegmentId(segment.id);
              }}
              onSegmentPlay={(segment) => {
                playFrom(segment.startMs);
                setChosenSegmentId(segment.id);
              }}
              onCurrentToggle={togglePlayback}
              onPlaySegment={playSegmentWindow}
              editingSegmentId={editingSegmentId}
              onEdit={openEditor}
              onSaveEdit={saveEdit}
              onDiscardEdit={() => {
                setEditError(null);
                setEditingSegmentId(null);
              }}
              onRevertEdit={revertEdit}
              editError={editError}
            />
          </TranscriptRegion>
        )}
      </Stack>
      {!following ? (
        <Button type="button" onClick={() => followModeSend({ type: 'RESUME' })}>
          Back to playing position
        </Button>
      ) : null}
      {deleteDialog}
    </Page>
  );
}
