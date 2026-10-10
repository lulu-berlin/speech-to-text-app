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
import { controlStyle } from '../../shared/ui/controlStyle';
import { Field } from '../../shared/ui/Field';
import { TextLink } from '../../shared/ui/TextLink';
import type { UploadOptions } from '../../shared/api/client';
import { languages } from '../../shared/api/fixtures';
import { ApiError } from '../../shared/api/types';
import type { Task, TaskStatus } from '../../shared/api/types';
import { ConnectionContext } from '../../app/ConnectionContext';
import { finishNoticeMachine } from './finishNoticeMachine';
import { dropZoneMachine } from './dropZoneMachine';
import type { FinishNotice } from './finishNoticeMachine';
import { focusRing } from '../../shared/ui/focusRing';
import { List } from '../../shared/ui/List';
import { ListRow } from '../../shared/ui/ListRow';
import { ProgressBar } from '../../shared/ui/ProgressBar';
import { SearchableSelect } from '../../shared/ui/SearchableSelect';
import { Switch } from '../../shared/ui/Switch';
import { TextButton } from '../../shared/ui/TextButton';
import { StenographerAnimation } from '../../shared/brand/StenographerAnimation';
import { Page, Row, Stack } from '../../shared/ui/layout';
import { PageHeading } from '../../shared/ui/typography';
import { toggleMachine } from '../../shared/state/toggleMachine';
import { queuePauseMachine } from './queuePauseMachine';
import { reorderMachine } from './reorderMachine';
import { RowContent, RowName } from './rowParts';
import { SortableTaskRow } from './SortableTaskRow';
import { uploadBatchMachine } from './uploadBatchMachine';
import type { UploadRow } from './uploadBatchMachine';
import { isSupportedFormat } from './uploadValidation';

// The upload options, one set for the whole batch (item 14): the language codes come
// from docs/api/languages.json (contract data, ADR 0029), speakers is auto/off/fixed with
// a count 1–20, max speakers 1–20 behind the advanced toggle.
const LANGUAGE_OPTIONS = languages.languages.map((language) => ({
  value: language.code,
  label: language.name,
}));

const SPEAKERS_CHOICES = ['auto', 'off', 'fixed'] as const;

export type SpeakersChoice = (typeof SPEAKERS_CHOICES)[number];

// The statuses the queue list holds (item 14): a task with any other status has left the
// queue (finished, failed or canceled) and belongs to History.
const PENDING_STATUSES = ['QUEUED', 'RUNNING', 'PAUSED'] as const;

// A task leaving the queue finished shows a short notice with a link (item 16).
const FINISHED_STATUSES = ['COMPLETED', 'FAILED', 'CANCELED'] as const;

function isSpeakersChoice(value: string): value is SpeakersChoice {
  return (SPEAKERS_CHOICES as readonly string[]).includes(value);
}

// Whether a task has left the queue finished (item 16); also narrows the status.
function finishedStatus(status: TaskStatus): status is 'COMPLETED' | 'FAILED' | 'CANCELED' {
  return (FINISHED_STATUSES as readonly string[]).includes(status);
}

const Select = styled.select`
  ${controlStyle}
`;

const NumberInput = styled.input`
  ${controlStyle}
`;

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
  background: ${(props) =>
    props.$over ? props.theme.colors.status.active.background : props.theme.colors.surface};
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

// The count inputs accept 1–20 only (item 12 contract): anything else is clamped, and a
// non-numeric value keeps the previous one.
function clampCount(raw: string, fallback: number): number {
  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed)) {
    return fallback;
  }
  return Math.min(20, Math.max(1, parsed));
}

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
// notice or the error message on a failed upload, and the status text otherwise.
function UploadRowTrailing({ row, tasks }: { row: UploadRow; tasks: Task[] }) {
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
  return <RowStatus>{row.status}</RowStatus>;
}

export function QueueView() {
  const connection = ConnectionContext.useActorRef();
  const { tasks, queue } = ConnectionContext.useSelector((state) => state.context);
  const live = ConnectionContext.useSelector((state) => state.value === 'live');
  const [language, setLanguage] = useState(languages.default);
  const [speakers, setSpeakers] = useState<SpeakersChoice>('auto');
  const [speakerCount, setSpeakerCount] = useState(2);
  const [maxSpeakers, setMaxSpeakers] = useState(8);
  const [advanced, sendAdvanced] = useMachine(toggleMachine);
  const [upload, sendToUploader] = useMachine(uploadBatchMachine);
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
  // reconciles into the displayed order (the machine ignores SYNC while sending).
  useEffect(() => {
    sendReorder({ type: 'SYNC', order: queue?.taskIds ?? [] });
  }, [queue, sendReorder]);

  const enqueue = (files: File[]) => {
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
    .filter(
      (task): task is Task =>
        task !== undefined && (PENDING_STATUSES as readonly string[]).includes(task.status),
    );

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
  const empty = live && pendingTasks.length === 0 && visibleRows.length === 0;

  return (
    <Page>
      <Stack gap="lg">
        <PageHeading>Queue</PageHeading>
        {notices.context.notices.length > 0 ? (
          <Stack gap="sm">
            {notices.context.notices.map((notice) => (
              <FinishNoticeLine key={notice.id} notice={notice} />
            ))}
          </Stack>
        ) : null}
        <Stack gap="md">
          <Field label="Language" htmlFor="language">
            <SearchableSelect
              id="language"
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
                onChange={(event) => setMaxSpeakers(clampCount(event.target.value, maxSpeakers))}
              />
            </Field>
          ) : null}
        </Stack>
        <DropZone
          type="button"
          $over={dragOver}
          onClick={() => inputRef.current?.click()}
          onDrop={onDrop}
          onDragOver={onDragOver}
          onDragEnter={() => sendDropZone({ type: 'ENTER' })}
          onDragLeave={() => sendDropZone({ type: 'LEAVE' })}
        >
          {empty ? (
            <EmptyZoneContent>
              <StenographerAnimation size="empty" />
              {dragOver
                ? 'Release to upload'
                : 'No tasks yet — drop audio files here, or click to choose them'}
            </EmptyZoneContent>
          ) : dragOver ? (
            'Release to upload'
          ) : (
            'Drop audio files here or click to choose'
          )}
        </DropZone>
        <input ref={inputRef} type="file" multiple hidden onChange={onPick} />
        {visibleRows.length > 0 ? (
          <List label="Uploads">
            {visibleRows.map((row) => (
              <ListRow key={row.id}>
                <RowContent>
                  <RowName>{row.file.name}</RowName>
                  <UploadRowTrailing row={row} tasks={tasks} />
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
            disabled={pause.matches('sending')}
          />
        </Row>
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
