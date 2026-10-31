import {
  useEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
} from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { Pencil, Play, Square } from 'lucide-react';
import styled, { useTheme } from 'styled-components';
import { Button } from '../../shared/ui/Button';
import type { Segment, Turn, TurnSpeaker } from '../../shared/api/types';
import { speakerTint } from '../../shared/theme/speakerPalette';
import { focusRing } from '../../shared/ui/focusRing';
import { Icon } from '../../shared/ui/Icon';
import { Stack } from '../../shared/ui/layout';
import { Text } from '../../shared/ui/typography';
import { formatDuration } from '../../shared/formatDuration';

// What a text-less segment shows in its place (item 52, user decision 2026-10-23): the view's
// placeholder only — the downloads render the stored text.
const EMPTY_TEXT = '[empty]';

// One turn of the reading column: its start time in a left gutter of one shared width,
// and the speaker's name on its own line above the turn's text. On a phone the gutter
// folds into one column, the time above the speaker.
const TurnRow = styled.div`
  display: grid;
  grid-template-columns: ${(props) => props.theme.sizes.timeGutter} 1fr;
  gap: ${(props) => props.theme.spacing.md};

  @media (max-width: ${(props) => props.theme.breakpoints.sm}) {
    grid-template-columns: 1fr;
  }
`;

// A turn's start time in the gutter: muted, small, fixed-width digits.
// A selection box of the reassignment mode (item 73's amendment): the accent when ticked and the
// shared focus ring, like every focusable control.
const SelectBox = styled.input`
  accent-color: ${(props) => props.theme.colors.accent};
  ${focusRing}
`;

const TurnGutter = styled.span`
  color: ${(props) => props.theme.colors.textMuted};
  font-size: ${(props) => props.theme.fontSizes.small};
  font-variant-numeric: tabular-nums;
`;

const TurnBody = styled.div`
  min-width: 0;
`;

// The turns list: the large gap between turns.
const TurnsList = styled(Stack)`
  gap: ${(props) => props.theme.spacing.lg};
`;

// The turn's text at the transcript's reading line height.
const TurnText = styled(Text)`
  margin: 0;
  line-height: ${(props) => props.theme.lineHeights.reading};
  overflow-wrap: anywhere;
`;

const SpeakerName = styled.span<{ $color: string }>`
  display: block;
  color: ${(props) => props.$color};
  font-weight: ${(props) => props.theme.fontWeights.medium};
`;

// A segment's text while the reassignment mode is on (item 73's amendment): plain reading text, with
// no button role, no keys and no pointer action, so the segment can only be ticked there.
const SegmentInert = styled.span<{ $empty: boolean }>`
  color: ${(props) => (props.$empty ? props.theme.colors.textMuted : 'inherit')};
`;

// A segment of a turn: inline text that moves the player to its start (items 9, 52), filled
// while it is the current segment — the playhead's, kept through a gap (item 52), the one
// mark there is. An inline element with the button role, not a <button>: a <button> is an
// inline-block box that wraps as a whole and centers its own lines, so a turn would not read
// as one paragraph (item 29).
const SegmentButton = styled.span<{ $current: boolean; $empty: boolean }>`
  background: ${(props) => (props.$current ? props.theme.colors.accent : 'transparent')};
  color: ${(props) =>
    props.$current
      ? props.theme.colors.accentText
      : props.$empty
        ? props.theme.colors.textMuted
        : 'inherit'};
  cursor: pointer;
  // Follow mode (item 31, item 59): the current segment is scrolled into view while
  // playing, and the browser honors the target's scroll margin for every scrolled
  // ancestor, so this top margin — the text-only compact header's height plus its border
  // — keeps the playing segment clear of the sticky header. The phone's wrapped header
  // is taller, so it reserves more there.
  scroll-margin-top: calc(
    ${(props) => props.theme.sizes.compactHeader} + ${(props) => props.theme.borderWidths.thin}
  );
  @media (max-width: ${(props) => props.theme.breakpoints.sm}) {
    scroll-margin-top: calc(
      ${(props) => props.theme.sizes.compactHeaderPhone} +
        ${(props) => props.theme.borderWidths.thin}
    );
  }
  ${focusRing}
`;

// A control of the current segment (item 52). An inline box in the text's own font, like
// the segment, so it is exactly the highlight's height and never raises the line (a button
// box with padding did, user report 2026-10-23); its icon is no taller than the text and sits
// in the middle of the line. A word space of margin parts it from the segment's frame (user
// report: it touched it); a margin, so its hover and focus boxes stay around the icon.
const SegmentControlBase = styled.span`
  margin-left: ${(props) => props.theme.spacing.xs};
  padding: 0;
  border-radius: ${(props) => props.theme.radii.button};
  cursor: pointer;

  &:hover {
    background: ${(props) => props.theme.colors.surfaceRaised};
  }

  > span {
    vertical-align: middle;
  }

  // Disabled (item 52: Edit while its segment plays on its own) — grayed out and not clickable,
  // like the other disabled controls.
  &[aria-disabled='true'] {
    color: ${(props) => props.theme.colors.textMuted};
    cursor: default;
    background: transparent;
  }
  ${focusRing}
`;

// A control of the current segment, keyboard-operable by construction (root review
// 2026-10-23): a span has no native activation, so Enter and Space are handled here.
function SegmentControl({
  label,
  onActivate,
  disabled = false,
  keepsFocusInField = false,
  keyshortcut,
  children,
}: {
  label: string;
  onActivate: () => void;
  disabled?: boolean;
  // A click must not take the focus from the editor's field (the user's review 2026-10-23): the
  // dialog's preview sets this, so the caret stays where it was.
  keepsFocusInField?: boolean;
  // The bare-letter shortcut the control answers to, advertised to assistive tech.
  keyshortcut?: string;
  children: ReactNode;
}) {
  return (
    <SegmentControlBase
      role="button"
      tabIndex={disabled ? -1 : 0}
      aria-label={label}
      aria-keyshortcuts={keyshortcut}
      aria-disabled={disabled || undefined}
      onMouseDown={keepsFocusInField ? (event) => event.preventDefault() : undefined}
      onClick={disabled ? undefined : onActivate}
      onKeyDown={
        disabled
          ? undefined
          : (event) => {
              if (event.key !== 'Enter' && event.key !== ' ') {
                return;
              }
              // Space here is never the transcript's scroll key: preventing the default keeps
              // the region's handler out of it.
              event.preventDefault();
              onActivate();
            }
      }
    >
      {children}
    </SegmentControlBase>
  );
}

// A segment's editor (item 52): a field pre-filled with the stored text. Enter or Ctrl+Enter
// save, Escape discards, and a rejected save's detail shows on its own line (all in
// SegmentEditor below). Since the user's review 2026-10-23 it is a **dialog** over the page
// (EditDialog), on Radix's dialog like ConfirmDialog: the page is shaded and inert, it does not
// scroll, and focus is trapped and restored — the editor is plainly the only thing active.
const Overlay = styled(Dialog.Overlay)`
  position: fixed;
  inset: 0;
  background: ${(props) => props.theme.colors.overlay};
`;

const Panel = styled(Dialog.Content)`
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 100%;
  max-width: ${(props) => props.theme.sizes.pageMaxWidth};
  max-height: 100%;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: ${(props) => props.theme.spacing.sm};
  padding: ${(props) => props.theme.spacing.lg};
  border: ${(props) => props.theme.borderWidths.thin} solid ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.dialog};
  background: ${(props) => props.theme.colors.surfaceRaised};
  color: ${(props) => props.theme.colors.text};
  box-shadow: ${(props) => props.theme.shadows.raised};
`;

const Title = styled(Dialog.Title)`
  margin: 0;
  font-size: ${(props) => props.theme.fontSizes.title};
`;

// The editor's row inside the dialog: field, a preview and three buttons, wrapping on a phone.
const EditorBox = styled.div`
  display: flex;
  align-items: center;
  gap: ${(props) => props.theme.spacing.xs};
  flex-wrap: wrap;
`;

// A rejected save's server detail (item 52): its own line under the field and buttons.
const EditorError = styled.span`
  flex-basis: 100%;
  color: ${(props) => props.theme.colors.danger};
  font-size: ${(props) => props.theme.fontSizes.small};
`;

const EditorArea = styled.textarea`
  flex: 1;
  min-width: 0;
  // A field keeps the browser's own text color (black) unless told otherwise, which was
  // illegible on the dark theme's surface (the user's review 2026-10-23).
  color: inherit;
  font: inherit;
  padding: 0;
  border: none;
  outline: none;
  background: transparent;
  resize: none;
`;

function SegmentEditor({
  text,
  edited,
  error,
  previewing,
  onPreview,
  onStopPreview,
  onSave,
  onDiscard,
  onRevert,
}: {
  text: string;
  edited: boolean;
  error: string | null;
  previewing: boolean;
  onPreview: () => void;
  onStopPreview: () => void;
  onSave: (draft: string) => void;
  onDiscard: () => void;
  onRevert: () => void;
}) {
  const [draft, setDraft] = useState(text);
  // Opening the editor is ready to type (the user's review 2026-10-23): the field takes focus
  // with the caret after the last character.
  const fieldRef = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const field = fieldRef.current;
    if (field === null) {
      return;
    }
    field.focus();
    field.setSelectionRange(field.value.length, field.value.length);
  }, []);
  // An unchanged draft is not a save (user rule 2026-10-23): the editor just closes, with no
  // request. Both sides trimmed, as the client trims what it stores.
  const commit = () => {
    if (draft.trim() === text.trim()) {
      onDiscard();
    } else {
      onSave(draft);
    }
  };
  // Alt+S / Alt+D (the underlined letters; Ctrl and Cmd work too, the forms the user also
  // expects) save and discard without leaving the field (root review 2026-10-23). Each label
  // stays one element: the Button is a flex row with a gap, so a bare `<u>S</u>ave` became two
  // flex items — visibly "S  ave" and, in Chromium, the accessible name "S ave" (CI, 2026-10-24).
  const dialogShortcuts = (event: ReactKeyboardEvent<HTMLElement>) => {
    if (!(event.altKey || event.ctrlKey || event.metaKey)) {
      return;
    }
    // `code` survives the modifier mangling the letter on macOS (Alt+S types ß there); `key`
    // covers synthetic events that carry no code.
    const letter = event.code === '' ? event.key.toLowerCase() : event.code;
    if (letter === 'KeyS' || letter === 's') {
      event.preventDefault();
      commit();
    } else if (letter === 'KeyD' || letter === 'd') {
      event.preventDefault();
      onDiscard();
    }
  };
  return (
    <EditorBox onKeyDown={dialogShortcuts}>
      <EditorArea
        ref={fieldRef}
        aria-label="Segment text"
        dir="auto"
        // A dialog has room: the field wraps a long segment over several rows (the user's
        // review 2026-10-23) — no line breaks are *stored* either way: every break in typed or
        // pasted text becomes a space, and Enter saves instead of breaking the line.
        rows={3}
        value={draft}
        onChange={(event) => setDraft(event.target.value.replace(/\r\n|\r|\n/g, ' '))}
        onKeyDown={(event) => {
          // Escape abandons the draft; Enter saves (Shift+Enter does nothing) (item 52).
          if (event.key === 'Escape') {
            event.preventDefault();
            onDiscard();
            return;
          }
          if (event.key !== 'Enter') {
            return;
          }
          event.preventDefault();
          if (!event.shiftKey) {
            commit();
          }
        }}
      />
      <SegmentControl
        label={previewing ? 'Stop segment' : 'Play segment'}
        keepsFocusInField
        onActivate={previewing ? onStopPreview : onPreview}
      >
        <Icon icon={previewing ? Square : Play} size="sm" />
      </SegmentControl>
      <Button type="button" size="sm" aria-keyshortcuts="Alt+S Control+S Meta+S" onClick={commit}>
        <span>
          <u>S</u>ave
        </span>
      </Button>
      {/* ADR 0061: whisper's original is there to go back to only when the text was edited. */}
      {edited ? (
        <Button type="button" size="sm" variant="secondary" onClick={onRevert}>
          Revert
        </Button>
      ) : null}
      <Button
        type="button"
        size="sm"
        variant="secondary"
        aria-keyshortcuts="Alt+D Control+D Meta+D"
        onClick={onDiscard}
      >
        <span>
          <u>D</u>iscard
        </span>
      </Button>
      {error === null ? null : <EditorError role="alert">{error}</EditorError>}
    </EditorBox>
  );
}

// The editor over the page: Radix's modal dialog, so the rest of the page is inert, the body
// does not scroll, focus stays inside and returns to the Edit control on close, and Escape
// closes it. A click on the shade leaves it open (the user's decision 2026-10-23).
function EditDialog({
  title,
  onDiscard,
  children,
}: {
  title: string;
  onDiscard: () => void;
  children: ReactNode;
}) {
  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open) {
          onDiscard();
        }
      }}
    >
      <Dialog.Portal>
        <Overlay />
        <Panel
          aria-describedby={undefined}
          onInteractOutside={(event) => event.preventDefault()}
          // Closing returns focus to the Edit control of the segment that was edited (the
          // user's review 2026-10-23) — it stays mounted under the dialog, so it is there to
          // take the focus.
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            const segment = document.querySelector<HTMLElement>('[data-active]');
            const edit =
              segment?.parentElement?.querySelector<HTMLElement>('[aria-label="Edit segment"]') ??
              segment;
            edit?.focus({ preventScroll: true });
          }}
        >
          <Title>{title}</Title>
          {children}
        </Panel>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

// The current segment's controls (item 52): Edit, then Play-segment. While the segment plays on
// its own, the second is its Stop (a square) in the same place, so a second click without moving
// stops it, and Edit stays beside it, disabled (the user's review 2026-10-23).
function SegmentControls({
  segment,
  playingOnItsOwn,
  onEdit,
  onPlaySegment,
  onStopSegment,
}: {
  segment: Segment;
  playingOnItsOwn: boolean;
  onEdit: (segment: Segment) => void;
  onPlaySegment: (segment: Segment) => void;
  onStopSegment: () => void;
}) {
  return (
    <>
      <SegmentControl
        label="Edit segment"
        keyshortcut="E"
        disabled={playingOnItsOwn}
        onActivate={() => onEdit(segment)}
      >
        <Icon icon={Pencil} size="sm" />
      </SegmentControl>
      <SegmentControl
        label={playingOnItsOwn ? 'Stop segment' : 'Play segment'}
        keyshortcut="P"
        onActivate={playingOnItsOwn ? onStopSegment : () => onPlaySegment(segment)}
      >
        <Icon icon={playingOnItsOwn ? Square : Play} size="sm" />
      </SegmentControl>
    </>
  );
}

export interface TranscriptTurnsProps {
  // Each turn with its speaker (undefined when speakers are off).
  turnsBySpeaker: { turn: Turn; speaker: TurnSpeaker | undefined }[];
  // Item 73's amendment: the reassignment mode is on, so every segment and turn has a selection box.
  reassigning: boolean;
  // The segment ids ticked for the move.
  selectedSegments: ReadonlySet<number>;
  // A segment's box changed.
  onToggleSegment: (segment: Segment) => void;
  // A turn's box changed: all of its segments, or none.
  onToggleTurn: (turn: Turn) => void;
  // The current segment (item 52): the playhead's, kept through a gap; null before the first.
  currentSegment: Segment | null;
  // Whether the audio is playing: the current segment's controls show only while nothing plays
  // (paused or stopped), so nothing moves under the pointer during playback (item 52) — except
  // for a segment playing on its own, whose Play-segment control is then its Stop.
  playing: boolean;
  // The segment playing on its own (item 52), or null.
  playingOnItsOwnId: number | null;
  // The Stop of a segment playing on its own was pressed.
  onStopSegment: () => void;
  // A segment was clicked: move the player to its start, which makes it current; playing
  // goes on if it was playing (item 52).
  onSegmentSeek: (segment: Segment) => void;
  // A segment was double-clicked, or activated by Enter/Space while not current: play from
  // its start.
  onSegmentPlay: (segment: Segment) => void;
  // Enter/Space on the current segment: pause if playing, resume where it paused if not
  // (item 52).
  onCurrentToggle: () => void;
  // The segment whose inline editor is open (item 52), or null.
  editingSegmentId: number | null;
  // The Edit control was pressed: open that segment's editor.
  onEdit: (segment: Segment) => void;
  // The editor's Save was pressed: store the draft text.
  onSaveEdit: (segment: Segment, text: string) => void;
  // The editor's Discard (or Escape) was pressed: close it, draft abandoned.
  onDiscardEdit: () => void;
  // The Play-segment control was pressed: play exactly the segment's window once.
  onPlaySegment: (segment: Segment) => void;
  // The editor's Revert was pressed (ADR 0061): put whisper's original text back.
  onRevertEdit: (segment: Segment) => void;
  // A rejected save's server detail, shown in the open editor (item 52), or null.
  editError: string | null;
}

// The transcript's turns in the reading column (item 25): the start time in the gutter,
// the speaker in their tint above the text, and the segments as seek buttons; the current
// segment is followed by its controls (item 52).
export function TranscriptTurns({
  turnsBySpeaker,
  reassigning,
  selectedSegments,
  onToggleSegment,
  onToggleTurn,
  currentSegment,
  playing,
  playingOnItsOwnId,
  onStopSegment,
  onSegmentSeek,
  onSegmentPlay,
  onCurrentToggle,
  onPlaySegment,
  editingSegmentId,
  onEdit,
  onSaveEdit,
  onDiscardEdit,
  onRevertEdit,
  editError,
}: TranscriptTurnsProps) {
  const tints = useTheme().colors.speakerTints;
  return (
    <TurnsList gap="lg">
      {turnsBySpeaker.map(({ turn, speaker }, index) => (
        <TurnRow key={index}>
          <TurnGutter>
            {reassigning ? (
              <SelectBox
                type="checkbox"
                aria-label={`Select turn starting ${formatDuration(turn.startMs)}`}
                checked={turn.segments.every((segment) => selectedSegments.has(segment.id))}
                onChange={() => onToggleTurn(turn)}
              />
            ) : null}{' '}
            {formatDuration(turn.startMs)}
          </TurnGutter>
          <TurnBody>
            <Stack gap="xs">
              {speaker !== undefined ? (
                <SpeakerName $color={speakerTint(tints, speaker.speakerId)}>
                  {speaker.speaker}
                </SpeakerName>
              ) : null}
              <TurnText>
                {turn.segments.map((segment) => {
                  // A segment whisper left empty (or whitespace only) is not a hole in the
                  // text: it shows a grayed-out placeholder and stays editable (item 52, user
                  // decision 2026-10-23). The placeholder is the view's alone — the downloads
                  // render the stored text.
                  const empty = segment.text.trim() === '';
                  const editing = segment.id === editingSegmentId;
                  return (
                    <span key={`${segment.startMs}-${segment.endMs}`}>
                      {reassigning ? (
                        <SelectBox
                          type="checkbox"
                          aria-label={`Select segment starting ${formatDuration(segment.startMs)}`}
                          checked={selectedSegments.has(segment.id)}
                          onChange={() => onToggleSegment(segment)}
                        />
                      ) : null}
                      {reassigning ? (
                        <SegmentInert $empty={empty}>
                          {empty ? EMPTY_TEXT : segment.text}
                        </SegmentInert>
                      ) : (
                        <SegmentButton
                          role="button"
                          tabIndex={0}
                          $current={segment === currentSegment}
                          $empty={empty}
                          data-active={segment === currentSegment || undefined}
                          aria-current={segment === currentSegment || undefined}
                          onClick={() => onSegmentSeek(segment)}
                          onDoubleClick={() => onSegmentPlay(segment)}
                          onKeyDown={(event) => {
                            // A native button's keys: Enter and Space activate, and Space must
                            // not scroll the page.
                            if (event.key === 'Enter' || event.key === ' ') {
                              event.preventDefault();
                              if (segment === currentSegment) {
                                onCurrentToggle();
                              } else {
                                onSegmentPlay(segment);
                              }
                            }
                          }}
                        >
                          {empty ? EMPTY_TEXT : segment.text}
                        </SegmentButton>
                      )}{' '}
                      {!reassigning &&
                      segment === currentSegment &&
                      (!playing || segment.id === playingOnItsOwnId) ? (
                        <SegmentControls
                          segment={segment}
                          playingOnItsOwn={segment.id === playingOnItsOwnId}
                          onEdit={onEdit}
                          onPlaySegment={onPlaySegment}
                          onStopSegment={onStopSegment}
                        />
                      ) : null}
                      {editing ? (
                        <EditDialog
                          title={
                            speaker === undefined
                              ? `${formatDuration(segment.startMs)}–${formatDuration(segment.endMs)}`
                              : `${speaker.speaker} · ${formatDuration(segment.startMs)}–${formatDuration(segment.endMs)}`
                          }
                          onDiscard={onDiscardEdit}
                        >
                          <SegmentEditor
                            text={segment.text}
                            edited={segment.edited}
                            error={editError}
                            previewing={segment.id === playingOnItsOwnId}
                            onPreview={() => onPlaySegment(segment)}
                            onStopPreview={onStopSegment}
                            onSave={(draft) => onSaveEdit(segment, draft)}
                            onDiscard={onDiscardEdit}
                            onRevert={() => onRevertEdit(segment)}
                          />
                        </EditDialog>
                      ) : null}{' '}
                    </span>
                  );
                })}
              </TurnText>
            </Stack>
          </TurnBody>
        </TurnRow>
      ))}
    </TurnsList>
  );
}
