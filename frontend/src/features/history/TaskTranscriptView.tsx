import { useEffect, useMemo, useRef } from 'react';
import { useMachine } from '@xstate/react';
import { useNavigate, useParams } from 'react-router';
import styled, { useTheme } from 'styled-components';
import { ConnectionContext } from '../../app/ConnectionContext';
import { errorMessage } from '../../shared/api/types';
import type { Task, TaskPhase } from '../../shared/api/types';
import { Page, Row, Stack } from '../../shared/ui/layout';
import { MetaText, PageHeading, Text } from '../../shared/ui/typography';
import { Button } from '../../shared/ui/Button';
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog';
import { focusRing } from '../../shared/ui/focusRing';
import { LoadingState } from '../../shared/ui/LoadingState';
import { ErrorCallout } from '../../shared/ui/ErrorCallout';
import { TextLink } from '../../shared/ui/TextLink';
import { turnsMachine } from './turnsMachine';
import { segmentAtTime } from './segmentTiming';
import { commandMachine } from '../../shared/state/commandMachine';
import { confirmActionMachine } from '../../shared/state/confirmActionMachine';
import { formatDuration, formatTaskMeta } from './formatTask';
import { TranscriptTurns } from './TranscriptTurns';
import { AudioPlayer } from './AudioPlayer';
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
  const audioRef = useRef<HTMLAudioElement>(null);

  // Moves the audio and the playhead to `ms`: the player's seek bar (on release) and
  // click-to-seek. The machine's SEEK also ends any drag draft.
  const seek = (ms: number) => {
    const audio = audioRef.current;
    if (audio === null) {
      return;
    }
    playbackSend({ type: 'SEEK', ms });
    audio.currentTime = ms / 1000;
  };

  // Clicking a segment seeks the player to the segment's start and plays from there
  // (item 9, decided 2026-10-09).
  const seekTo = (startMs: number) => {
    seek(startMs);
    audioRef.current?.play().catch(() => {
      /* A browser may refuse playback before any user gesture (autoplay policy); the seek
         itself still landed, so the highlight follows. */
    });
  };

  // All segments in time order, so the highlight can be a binary search over the starts.
  const segmentsInOrder = useMemo(
    () => (turns === null ? [] : turns.turns.map((turn) => turn.segments).flat()),
    [turns],
  );
  const activeSegment = segmentAtTime(segmentsInOrder, currentTimeMs);

  // Follow mode (item 10): while the playhead moves and the user has not scrolled by hand,
  // the active segment is kept in view. A hand scroll stops following; "Back to playing
  // position" resumes it.
  const [followMode, followModeSend] = useMachine(followModeMachine);
  const following = followMode.matches('following');
  const transcriptRegionRef = useRef<HTMLDivElement>(null);
  // True right after we scroll the active segment ourselves, so the scroll event that
  // programmatic follow fires is not mistaken for the user's hand (cleared on the next
  // macrotask, by which a hand scroll would arrive separately).
  const holdOffFollowScrollRef = useRef(false);

  useEffect(() => {
    if (!following || activeSegment === null) {
      return;
    }
    // The active segment is where the highlight is; scroll it into the reader's sight.
    const active = transcriptRegionRef.current?.querySelector('[data-active]');
    if (active === null || active === undefined) {
      return;
    }
    holdOffFollowScrollRef.current = true;
    active.scrollIntoView({ block: 'nearest' });
    setTimeout(() => {
      holdOffFollowScrollRef.current = false;
    }, 0);
  }, [following, activeSegment]);

  // A scroll while the playhead is not moving is the user scrolling by hand. Our own
  // programmatic follow scroll is held off, so it does not unwind following.
  const handleScroll = () => {
    if (holdOffFollowScrollRef.current) {
      return;
    }
    followModeSend({ type: 'SCROLLED' });
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
      playing={playback.matches('playing')}
      audioRef={audioRef}
      onPlayhead={(ms) => playbackSend({ type: 'PLAYHEAD', ms })}
      onSeek={seek}
      onDrag={(ms) => playbackSend({ type: 'DRAG', ms })}
      onPlayingChange={(playing) => playbackSend({ type: playing ? 'PLAYED' : 'PAUSED' })}
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
            <Button
              type="button"
              variant="danger"
              onClick={() => {
                if (deleteState.matches('failed') || deleteState.matches('done')) {
                  deleteActor.stop();
                  deleteActor.start();
                }
                deleteSend({ type: 'REQUEST' });
              }}
            >
              Delete
            </Button>
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
          <Button
            type="button"
            variant="danger"
            onClick={() => {
              if (deleteState.matches('failed') || deleteState.matches('done')) {
                deleteActor.stop();
                deleteActor.start();
              }
              deleteSend({ type: 'REQUEST' });
            }}
          >
            Delete
          </Button>
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
            onScroll={handleScroll}
          >
            <TranscriptTurns
              turnsBySpeaker={turnsBySpeaker}
              activeSegment={activeSegment}
              onSegmentClick={seekTo}
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
