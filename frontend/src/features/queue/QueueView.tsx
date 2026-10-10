import { DndContext, KeyboardSensor, PointerSensor, useSensor, useSensors } from '@dnd-kit/core';
import type { DragEndEvent } from '@dnd-kit/core';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { useMachine } from '@xstate/react';
import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent, DragEvent, ReactNode } from 'react';
import { Link } from 'react-router';
import { css, styled } from 'styled-components';
import type { UploadOptions } from '../../shared/api/client';
import { languages } from '../../shared/api/fixtures';
import { ApiError } from '../../shared/api/types';
import type { Task } from '../../shared/api/types';
import { ConnectionContext } from '../../app/ConnectionContext';
import { focusRing } from '../../shared/ui/focusRing';
import { List } from '../../shared/ui/List';
import { ListRow } from '../../shared/ui/ListRow';
import { ProgressBar } from '../../shared/ui/ProgressBar';
import { SearchableSelect } from '../../shared/ui/SearchableSelect';
import { Page, Stack } from '../../shared/ui/layout';
import { PageHeading } from '../../shared/ui/typography';
import { toggleMachine } from '../../shared/state/toggleMachine';
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

  ${focusRing}
`;

const DropZone = styled.button`
  display: block;
  width: 100%;
  padding: ${(props) => props.theme.spacing.xl};
  border: ${(props) => props.theme.borderWidths.thin} dashed ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.card};
  background: ${(props) => props.theme.colors.surface};
  color: ${(props) => props.theme.colors.textMuted};
  font: inherit;
  cursor: pointer;

  ${focusRing}
`;

const RowStatus = styled.span`
  color: ${(props) => props.theme.colors.textMuted};
`;

const RowError = styled.span`
  color: ${(props) => props.theme.colors.danger};
`;

const ReorderError = styled.p`
  margin: 0;
  color: ${(props) => props.theme.colors.danger};
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
      Already uploaded — <Link to={duplicateTarget(tasks, taskId)}>open</Link>
    </RowError>
  );
}

// The trailing side of an upload row: the progress bar while uploading, the duplicate
// notice or the error message on a failed upload, and the status text otherwise.
function UploadRowTrailing({ row, tasks }: { row: UploadRow; tasks: Task[] }) {
  if (row.status === 'uploading') {
    return <ProgressBar value={row.progress} label={`Uploading ${row.file.name}`} />;
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
  const { tasks, queue } = ConnectionContext.useSelector((state) => state.context);
  const [language, setLanguage] = useState(languages.default);
  const [speakers, setSpeakers] = useState<SpeakersChoice>('auto');
  const [speakerCount, setSpeakerCount] = useState(2);
  const [maxSpeakers, setMaxSpeakers] = useState(8);
  const [advanced, sendAdvanced] = useMachine(toggleMachine);
  const [upload, sendToUploader] = useMachine(uploadBatchMachine);
  const [reorder, sendReorder] = useMachine(reorderMachine, {
    input: { order: queue?.taskIds ?? [] },
  });
  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  const inputRef = useRef<HTMLInputElement>(null);

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
    enqueue(Array.from(event.dataTransfer.files));
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

  return (
    <Page>
      <Stack gap="lg">
        <PageHeading>Queue</PageHeading>
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
          <AdvancedToggle
            type="button"
            aria-expanded={advanced.matches('open')}
            onClick={() => sendAdvanced({ type: 'TOGGLE' })}
          >
            Advanced
          </AdvancedToggle>
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
        <DropZone type="button" onClick={() => inputRef.current?.click()} onDrop={onDrop}>
          Drop audio files here or click to choose
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
