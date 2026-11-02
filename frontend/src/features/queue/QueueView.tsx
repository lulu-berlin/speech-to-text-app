import { DndContext, KeyboardSensor, PointerSensor, useSensor, useSensors } from '@dnd-kit/core';
import type { DragEndEvent } from '@dnd-kit/core';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { useMachine } from '@xstate/react';
import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent, DragEvent } from 'react';
import { styled } from 'styled-components';
import { UploadOptionsPanel } from './UploadOptionsPanel';
import type { SpeakersChoice } from './UploadOptionsPanel';
import { TextLink } from '../../shared/ui/TextLink';
import type { UploadOptions } from '../../shared/api/client';
import { languages } from '../../shared/api/fixtures';
import { ApiError, finishedStatus, pendingStatus } from '../../shared/api/types';
import type { Settings, Task, TaskStatus } from '../../shared/api/types';
import { ConnectionContext, useWriteControl } from '../../app/ConnectionContext';
import { finishNoticeMachine } from './finishNoticeMachine';
import { dropZoneMachine } from './dropZoneMachine';
import { DeviceFallbackBanner } from './DeviceFallbackBanner';
import type { FinishNotice } from './finishNoticeMachine';
import { errorMessage } from '../../shared/errorMessage';
import { ErrorCallout } from '../../shared/ui/ErrorCallout';
import { focusRing } from '../../shared/ui/focusRing';
import { List } from '../../shared/ui/List';
import { ListRow } from '../../shared/ui/ListRow';
import { ProgressBar } from '../../shared/ui/ProgressBar';
import { Switch } from '../../shared/ui/Switch';
import { StenographerAnimation } from '../../shared/brand/StenographerAnimation';
import { Page, Row, Stack } from '../../shared/ui/layout';
import { PageHeading } from '../../shared/ui/typography';
import { SettingsContext } from '../../app/SettingsContext';
import { queuePauseMachine } from './queuePauseMachine';
import { reorderMachine } from './reorderMachine';
import { RowContent, RowName } from './rowParts';
import { SortableTaskRow } from './SortableTaskRow';
import { uploadBatchMachine } from './uploadBatchMachine';
import type { UploadRow } from './uploadBatchMachine';
import { isSupportedFormat } from './uploadValidation';

// The statuses the queue list holds (item 14): a task with any other status has left the
// queue (finished, failed or canceled) and belongs to History.
// The drop zone: dashed on the surface fill; while files are dragged over it, a solid
// accent border on the light accent tint (item 25).
const DropZone = styled.button<{ $over: boolean }>`
  display: block;
  width: 100%;
  padding: ${(props) => props.theme.spacing.xl};
  border: ${(props) => props.theme.borderWidths.thin}
    ${(props) => (props.$over ? 'solid' : 'dashed')}
    ${(props) => (props.$over ? props.theme.colors.accent : props.theme.colors.border)};
  border-radius: ${(props) => props.theme.radii.card};
  background: ${(props) => (props.$over ? props.theme.colors.accentTint : props.theme.colors.surface)};
  color: ${(props) => props.theme.colors.textMuted};
  font: inherit;
  cursor: pointer;

  ${focusRing}
`;

// The drop zone's empty-state content (item 25): the mascot above the one line of
// guidance, centred; the zone itself stays the action.
const EmptyZoneContent = styled.span`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: ${(props) => props.theme.spacing.sm};
`;

// What the drop zone says: "Release to upload" while files are over it; otherwise the
// empty state's guidance (with the typing mascot above it — item 59: nothing runs while the
// queue is empty, so the piece is charm, not a running signal) or the compact line. The
// mascot stays while dragging over the empty state.
function DropZoneContent({ empty, over }: { empty: boolean; over: boolean }) {
  const label = over
    ? 'Release to upload'
    : empty
      ? 'No tasks yet — drop audio files here, or click to choose them'
      : 'Drop audio files here or click to choose';
  if (!empty) {
    return label;
  }
  return (
    <EmptyZoneContent>
      <StenographerAnimation size="empty" typing />
      {label}
    </EmptyZoneContent>
  );
}

const RowStatus = styled.span`
  color: ${(props) => props.theme.colors.textMuted};
`;

// An uploading file's progress bar beside its filename: a fixed width that does not
// shrink, so the track never collapses; under the filename it spans the row (item 25).
const UploadProgress = styled.div`
  width: ${(props) => props.theme.sizes.uploadBar};
  flex-shrink: 0;

  @media (max-width: ${(props) => props.theme.breakpoints.sm}) {
    width: 100%;
  }
`;

const RowError = styled.span`
  color: ${(props) => props.theme.colors.danger};
`;

const ReorderError = styled.p`
  margin: 0;
  color: ${(props) => props.theme.colors.danger};
`;

// The link target of a duplicate upload: the existing task's queue row while it is
// pending, its transcript once it is finished. A task the state does not hold yet is
// treated as pending (the queue view is the safe default).
function duplicateTarget(tasks: Task[], taskId: number): string {
  const existing = tasks.find((task) => task.id === taskId);
  if (
    existing === undefined ||
    existing.status === 'QUEUED' ||
    existing.status === 'RUNNING' ||
    existing.status === 'PAUSED'
  ) {
    return '/queue';
  }
  return `/history/${taskId}`;
}

// The inline error of a duplicate upload: "Already uploaded" with a link to the existing
// task (item 14).
function DuplicateNotice({ taskId, tasks }: { taskId: number; tasks: Task[] }) {
  return (
    <RowError>
      Already uploaded — <TextLink to={duplicateTarget(tasks, taskId)}>open</TextLink>
    </RowError>
  );
}

// One finished task's notice: "... transcribed — open" (to its transcript) or
// "... failed/canceled — see History" (item 16).
function FinishNoticeLine({ notice }: { notice: FinishNotice }) {
  if (notice.status === 'COMPLETED') {
    return (
      <span>
        {notice.filename} transcribed — <TextLink to={`/history/${notice.id}`}>open</TextLink>
      </span>
    );
  }
  return (
    <span>
      {notice.filename} {notice.status === 'FAILED' ? 'failed' : 'canceled'} —{' '}
      <TextLink to="/history">see History</TextLink>
    </span>
  );
}

// The trailing side of an upload row: the progress bar while uploading, the duplicate
// notice or the error message on a failed upload, and the status text otherwise. A row the
// batch is holding for the connection says so, not the internal "queued" (item 87).
function UploadRowTrailing({
  row,
  tasks,
  waiting,
}: {
  row: UploadRow;
  tasks: Task[];
  waiting: boolean;
}) {
  if (row.status === 'uploading') {
    return (
      <UploadProgress>
        <ProgressBar value={row.progress} label={`Uploading ${row.file.name}`} />
      </UploadProgress>
    );
  }
  if (row.status === 'error') {
    return row.duplicateTaskId !== undefined ? (
      <DuplicateNotice taskId={row.duplicateTaskId} tasks={tasks} />
    ) : (
      <RowError>{row.error}</RowError>
    );
  }
  return (
    <RowStatus>
      {waiting && row.status === 'queued' ? 'Waiting for the connection…' : row.status}
    </RowStatus>
  );
}

/** One upload option a batch carries; the prefill remembers which ones the user has changed. */
type BatchOption = 'language' | 'speakersMode' | 'speakersCount' | 'maxSpeakers';

export function QueueView() {
  const connection = ConnectionContext.useActorRef();
  const { tasks, queue } = ConnectionContext.useSelector((state) => state.context);
  const writes = useWriteControl();
  const [language, setLanguage] = useState(languages.default);
  const [speakers, setSpeakers] = useState<SpeakersChoice>('auto');
  const [speakerCount, setSpeakerCount] = useState(2);
  const [maxSpeakers, setMaxSpeakers] = useState(8);
  const [upload, sendToUploader] = useMachine(uploadBatchMachine, {
    input: { live: !writes.disabled },
  });
  const settings = SettingsContext.useSelector((state) => state);
  const [reorder, sendReorder] = useMachine(reorderMachine, {
    input: { order: queue?.taskIds ?? [] },
  });
  const [pause, sendPause] = useMachine(queuePauseMachine);
  const [notices, sendNotice] = useMachine(finishNoticeMachine);
  const [dropZone, sendDropZone] = useMachine(dropZoneMachine);
  const dragOver = dropZone.matches('over');
  const previousStatusesRef = useRef<Record<number, TaskStatus>>({});
  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  const inputRef = useRef<HTMLInputElement>(null);
  // The upload options the user has changed: those keep the user's value, the rest are prefilled
  // from the stored settings when they arrive (roadmap item 84).
  const batchTouched = useRef(new Set<BatchOption>());

  // The upload options start with the stored defaults (item 32): language, speakers mode
  // and count, max speakers. Each field is prefilled once, when the settings arrive, and only
  // while the user has not changed it: a choice made while the settings are still loading is
  // this batch's, not the settings' (roadmap item 84: CI run 471's upload carried the default
  // language because the prefill landed after the user's pick). Until then (or if loading
  // fails) the contract defaults above stand.
  const stored = settings.context.settings;
  const prefilledFor = useRef<Settings | null>(null);
  useEffect(() => {
    if (stored === null || prefilledFor.current === stored) {
      return;
    }
    prefilledFor.current = stored;
    if (!batchTouched.current.has('language')) {
      setLanguage(stored.defaultLanguage);
    }
    if (!batchTouched.current.has('speakersMode')) {
      setSpeakers(stored.speakersMode);
    }
    if (!batchTouched.current.has('speakersCount') && stored.speakersCount !== null) {
      setSpeakerCount(stored.speakersCount);
    }
    if (!batchTouched.current.has('maxSpeakers')) {
      setMaxSpeakers(stored.maxSpeakers);
    }
  }, [stored]);

  // The batch starts, stops and resends only as the live stream allows (item 87).
  useEffect(() => {
    sendToUploader({ type: 'CONNECTION', live: !writes.disabled });
  }, [sendToUploader, writes.disabled]);

  // The pause POST answered: apply its queue to the live state (the server's own
  // queue-updated carries the same queue, so applying it twice is harmless).
  useEffect(() => {
    if (pause.context.queue !== null) {
      connection.send({ type: 'queue-updated', queue: pause.context.queue });
    }
  }, [pause.context.queue, connection]);

  // A task that just turned finished (its status left the pending group) gets a notice;
  // the statuses of the initial snapshot are the baseline, so already-finished tasks do
  // not notice.
  useEffect(() => {
    const previous = previousStatusesRef.current;
    for (const task of tasks) {
      const was = previous[task.id];
      if (was !== undefined && !finishedStatus(was) && finishedStatus(task.status)) {
        sendNotice({
          type: 'NOTICE',
          notice: { id: task.id, filename: task.filename, status: task.status },
        });
      }
    }
    const nextStatuses: Record<number, TaskStatus> = {};
    for (const task of tasks) {
      nextStatuses[task.id] = task.status;
    }
    previousStatusesRef.current = nextStatuses;
  }, [tasks, sendNotice]);

  // Follow the live queue while no reorder is in flight: a new upload or a removal
  // reconciles into the displayed order (the machine ignores SYNC while sending). The
  // tasks still pending go along, so a queue snapshot that arrives out of order cannot
  // hide a pending row (roadmap item 84). The pending set travels as one string: a
  // progress tick changes the task objects but not the set, so the sync only re-runs when
  // the set really changes — a needless one would clear a reorder error the user is
  // reading.
  const pendingKey = tasks
    .filter((task) => pendingStatus(task.status))
    .map((task) => task.id)
    .join(',');
  useEffect(() => {
    sendReorder({
      type: 'SYNC',
      order: queue?.taskIds ?? [],
      pending: pendingKey === '' ? [] : pendingKey.split(',').map(Number),
    });
  }, [queue, pendingKey, sendReorder]);

  const enqueue = (files: File[]) => {
    if (writes.disabled) {
      return;
    }
    const options: UploadOptions = {
      language,
      speakers: speakers === 'fixed' ? String(speakerCount) : speakers,
      maxSpeakers,
    };
    sendToUploader({ type: 'DROP', files: files.map((file) => ({ file, options })) });
  };

  const onDrop = (event: DragEvent<HTMLButtonElement>) => {
    event.preventDefault();
    sendDropZone({ type: 'DROP' });
    enqueue(Array.from(event.dataTransfer.files));
  };

  // A browser only permits a drop when the dragOver's default is prevented; the copy
  // effect tells it to show the copy cursor over the zone.
  const onDragOver = (event: DragEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
  };

  const onPick = (event: ChangeEvent<HTMLInputElement>) => {
    enqueue(Array.from(event.target.files ?? []));
    // Allow picking the same file again: a file input does not fire change for a value
    // it already holds.
    event.target.value = '';
  };

  // A dropped drag moves the active task to the position of the one it landed on.
  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (over === null || active.id === over.id) {
      return;
    }
    const id = Number(active.id);
    sendReorder({ type: 'MOVE', id, to: reorder.context.order.indexOf(Number(over.id)) });
  };

  // The pending tasks (QUEUED, RUNNING, PAUSED) in displayed order (the reorder machine
  // owns the order; docs/api/queue.json feeds it). A task that left the queue (canceled,
  // finished) stays out even if its id lingers in the queue-updated event for a moment,
  // so its row leaves at once.
  const pendingTasks = reorder.context.order
    .map((id) => tasks.find((task) => task.id === id))
    .filter((task): task is Task => task !== undefined && pendingStatus(task.status));

  // The upload rows the view shows: a successful row leaves once its task is in the
  // live state (the server announces a new upload with task-updated; item 13's machine
  // adds it). The rows themselves stay in the machine, waiting for their task to
  // appear; the uploader itself runs inside the machine.
  const visibleRows = upload.context.rows.filter(
    (row) =>
      row.status !== 'done' ||
      row.taskId === undefined ||
      !tasks.some((task) => task.id === row.taskId),
  );

  // The queue is empty once the snapshot has landed and nothing is pending or uploading;
  // before that the shell's connecting region is the loading state (item 25).
  const empty = !writes.disabled && pendingTasks.length === 0 && visibleRows.length === 0;

  return (
    <Page>
      <Stack gap="lg">
        <PageHeading>Queue</PageHeading>
        <DeviceFallbackBanner />
        {notices.context.notices.length > 0 ? (
          <Stack gap="sm">
            {notices.context.notices.map((notice) => (
              <FinishNoticeLine key={notice.id} notice={notice} />
            ))}
          </Stack>
        ) : null}
        <DropZone
          type="button"
          $over={dragOver}
          disabled={writes.disabled}
          title={writes.title}
          onClick={() => inputRef.current?.click()}
          onDrop={onDrop}
          onDragOver={onDragOver}
          onDragEnter={() => sendDropZone({ type: 'ENTER' })}
          onDragLeave={() => sendDropZone({ type: 'LEAVE' })}
        >
          <DropZoneContent empty={empty} over={dragOver} />
        </DropZone>
        <input
          ref={inputRef}
          type="file"
          multiple
          hidden
          disabled={writes.disabled}
          onChange={onPick}
        />
        <UploadOptionsPanel
          language={language}
          onLanguageChange={(next) => {
            batchTouched.current.add('language');
            setLanguage(next);
          }}
          speakers={speakers}
          onSpeakersChange={(next) => {
            batchTouched.current.add('speakersMode');
            setSpeakers(next);
          }}
          speakerCount={speakerCount}
          onSpeakerCountChange={(next) => {
            batchTouched.current.add('speakersCount');
            setSpeakerCount(next);
          }}
          maxSpeakers={maxSpeakers}
          onMaxSpeakersChange={(next) => {
            batchTouched.current.add('maxSpeakers');
            setMaxSpeakers(next);
          }}
        />
        {visibleRows.length > 0 ? (
          <List label="Uploads">
            {visibleRows.map((row) => (
              <ListRow key={row.id}>
                <RowContent>
                  <RowName>{row.file.name}</RowName>
                  <UploadRowTrailing row={row} tasks={tasks} waiting={upload.matches('waiting')} />
                </RowContent>
              </ListRow>
            ))}
          </List>
        ) : null}
        <Row gap="sm">
          <span>Pause the queue</span>
          <Switch
            checked={queue?.paused ?? false}
            onCheckedChange={() => sendPause({ type: 'TOGGLE', paused: !(queue?.paused ?? false) })}
            label="Pause the queue"
            disabled={pause.matches('sending') || writes.disabled}
            title={writes.title}
          />
        </Row>
        {pause.context.error !== null ? (
          <ErrorCallout
            title={
              pause.context.attempted === true
                ? 'Could not pause the queue'
                : 'Could not resume the queue'
            }
            detail={errorMessage(pause.context.error)}
          />
        ) : null}
        {pendingTasks.length > 0 ? (
          <DndContext sensors={sensors} onDragEnd={onDragEnd}>
            <SortableContext items={reorder.context.order} strategy={verticalListSortingStrategy}>
              <List label="Pending tasks">
                {pendingTasks.map((task, index) => (
                  <SortableTaskRow
                    key={task.id}
                    task={task}
                    index={index}
                    count={pendingTasks.length}
                    onMoveTask={(id, to) => sendReorder({ type: 'MOVE', id, to })}
                  />
                ))}
              </List>
            </SortableContext>
          </DndContext>
        ) : null}
        {reorder.context.error !== null ? (
          <ReorderError>{reorder.context.error}</ReorderError>
        ) : null}
      </Stack>
    </Page>
  );
}
