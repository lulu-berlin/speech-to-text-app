import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent, DragEvent, ReactNode } from 'react';
import { Link } from 'react-router';
import { css, styled } from 'styled-components';
import { uploadTask } from '../../shared/api/client';
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
import { StatusBadge } from '../../shared/ui/StatusBadge';
import { Page, Stack } from '../../shared/ui/layout';
import { PageHeading } from '../../shared/ui/typography';
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

const RowContent = styled.div`
  display: flex;
  justify-content: space-between;
  gap: ${(props) => props.theme.spacing.sm};
`;

const RowName = styled.span`
  color: ${(props) => props.theme.colors.text};
`;

const RowStatus = styled.span`
  color: ${(props) => props.theme.colors.textMuted};
`;

const RowError = styled.span`
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

// One file of the batch, with the options it was dropped under. The rows drive the
// uploader: the first queued row uploads, then the next, one at a time in drop order.
interface UploadRow {
  id: number;
  file: File;
  options: UploadOptions;
  status: 'queued' | 'uploading' | 'done' | 'error';
  // Upload progress 0..1 while uploading (bytes sent).
  progress: number;
  error: string | null;
  // The task a successful upload created; the row leaves once it is in the live state.
  taskId?: number;
  // The existing task a duplicate upload names; the row links to it.
  duplicateTaskId?: number;
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
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [rows, setRows] = useState<UploadRow[]>([]);
  const [busy, setBusy] = useState(false);
  const nextRowId = useRef(0);
  const inputRef = useRef<HTMLInputElement>(null);

  const enqueue = (files: File[]) => {
    const options: UploadOptions = {
      language,
      speakers: speakers === 'fixed' ? String(speakerCount) : speakers,
      maxSpeakers,
    };
    setRows((current) => [
      ...current,
      ...files.map((file) => {
        // Item 12 contract: the extension allow-list is checked client-side, so an
        // unsupported file never uploads.
        const supported = isSupportedFormat(file.name);
        return {
          id: ++nextRowId.current,
          file,
          options,
          status: supported ? ('queued' as const) : ('error' as const),
          progress: 0,
          error: supported ? null : 'Unsupported file format',
          taskId: undefined,
          duplicateTaskId: undefined,
        };
      }),
    ]);
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

  // The pending tasks (QUEUED, RUNNING, PAUSED) in queue order (docs/api/queue.json).
  const pendingTasks = (queue?.taskIds ?? [])
    .map((id) => tasks.find((task) => task.id === id))
    .filter((task): task is Task => task !== undefined);

  // A successful upload row leaves once its task is in the live state (the server
  // announces a new upload with task-updated; item 13's machine adds it).
  useEffect(() => {
    setRows((current) => {
      const remaining = current.filter(
        (row) =>
          row.status !== 'done' ||
          row.taskId === undefined ||
          !tasks.some((task) => task.id === row.taskId),
      );
      return remaining.length === current.length ? current : remaining;
    });
  }, [tasks]);

  // The uploader: while no upload is running, start the first queued row. When it
  // finishes (done or error), the effect runs again and starts the next one.
  useEffect(() => {
    if (busy) {
      return;
    }
    const next = rows.find((row) => row.status === 'queued');
    if (next === undefined) {
      return;
    }
    setBusy(true);
    setRows((current) =>
      current.map((row) => (row.id === next.id ? { ...row, status: 'uploading' } : row)),
    );
    uploadTask(next.file, next.options, (sentBytes, totalBytes) => {
      const fraction = totalBytes > 0 ? sentBytes / totalBytes : 0;
      setRows((current) =>
        current.map((row) => (row.id === next.id ? { ...row, progress: fraction } : row)),
      );
    })
      .then((task) => {
        setRows((current) =>
          current.map((row) =>
            row.id === next.id ? { ...row, status: 'done', taskId: task.id } : row,
          ),
        );
      })
      .catch((error: unknown) => {
        if (
          error instanceof ApiError &&
          error.code === 'duplicate-audio' &&
          error.taskId !== undefined
        ) {
          setRows((current) =>
            current.map((row) =>
              row.id === next.id
                ? { ...row, status: 'error', error: null, duplicateTaskId: error.taskId }
                : row,
            ),
          );
          return;
        }
        const message = error instanceof Error ? error.message : String(error);
        setRows((current) =>
          current.map((row) =>
            row.id === next.id ? { ...row, status: 'error', error: message } : row,
          ),
        );
      })
      .finally(() => setBusy(false));
  }, [rows, busy]);

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
            aria-expanded={advancedOpen}
            onClick={() => setAdvancedOpen((open) => !open)}
          >
            Advanced
          </AdvancedToggle>
          {advancedOpen ? (
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
        {rows.length > 0 ? (
          <List label="Uploads">
            {rows.map((row) => (
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
          <List label="Pending tasks">
            {pendingTasks.map((task) => (
              <ListRow key={task.id}>
                <RowContent>
                  <RowName>{task.filename}</RowName>
                  <StatusBadge status={task.status} />
                </RowContent>
              </ListRow>
            ))}
          </List>
        ) : null}
      </Stack>
    </Page>
  );
}
