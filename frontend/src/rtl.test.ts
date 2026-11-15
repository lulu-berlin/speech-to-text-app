import { describe, expect, it } from 'vitest';

// Item 88's RTL item: the shell lays itself out with CSS logical properties, so an RTL app
// language mirrors it (navigation, rows, the speakers panel, the player and the settings
// form); the transcript keeps its own direction per item 76. The shell's icons are
// direction-neutral by construction — a directional icon has to be mirrored for the other
// direction rather than silently point the wrong way.
const SHELL = /^\/src\/(?:app|features|shared\/ui)\//;
const TRANSCRIPT_TURNS = '/src/features/history/TranscriptTurns.tsx';

// Every source file's raw text, keyed by its path from the project root (the pattern the
// design-value convention uses).
const sources = import.meta.glob<string>('/src/**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
});

const PHYSICAL_RULES: ReadonlyArray<{ rule: string; pattern: RegExp }> = [
  { rule: 'physical side', pattern: /\b(?:margin|padding|border)-(?:left|right)\b/ },
  { rule: 'physical text alignment', pattern: /\btext-align\s*:\s*(?:left|right)\b/ },
  { rule: 'physical float', pattern: /\bfloat\s*:\s*(?:left|right)\b/ },
  // The physical inset of an absolutely positioned box. `50%` stays: it is the centred
  // dialogs' direction-neutral middle rather than a reading-direction alignment.
  { rule: 'physical inset', pattern: /^\s*(?:left|right)\s*:\s*(?!\s*50%)/ },
];

// The lucide icons whose meaning depends on the reading direction: their arrow points one
// way, so the shell must mirror them for the other direction rather than render them as is.
const DIRECTIONAL_ICONS: ReadonlySet<string> = new Set([
  'ArrowLeft',
  'ArrowRight',
  'ArrowUpLeft',
  'ArrowUpRight',
  'ArrowDownLeft',
  'ArrowDownRight',
  'ChevronLeft',
  'ChevronRight',
  'ChevronsLeft',
  'ChevronsRight',
  'MoveLeft',
  'MoveRight',
  'CornerDownLeft',
  'CornerDownRight',
  'CornerUpLeft',
  'CornerUpRight',
  'SkipBack',
  'SkipForward',
  'Undo2',
  'Redo2',
  'PanelLeft',
  'PanelRight',
  'LogIn',
  'LogOut',
  'Reply',
  'Forward',
  'AlignLeft',
  'AlignRight',
]);

function lucideIconImports(source: string): string[] {
  return [...source.matchAll(/import\s+(?:type\s+)?\{([^}]*)\}\s*from\s*'lucide-react'/g)]
    .flatMap((match) => (match[1] ?? '').split(','))
    .map((name) => name.trim())
    .filter((name) => name !== '');
}

function shellSources(): Array<[string, string]> {
  return Object.entries(sources).filter(
    ([file]) => SHELL.test(file) && !/\.test\.tsx?$/.test(file),
  );
}

describe('the RTL layout', () => {
  it('lays the shell out with logical properties, keeps its icons direction-neutral and the transcript’s own direction', () => {
    const violations = shellSources().flatMap(([file, source]) =>
      source.split('\n').flatMap((text, index) =>
        PHYSICAL_RULES.flatMap(({ rule, pattern }) => {
          const match = text.match(pattern);
          return match === null ? [] : [{ file, line: index + 1, rule, match: match[0] }];
        }),
      ),
    );
    expect(violations).toEqual([]);

    const directional = shellSources().flatMap(([file, source]) =>
      lucideIconImports(source)
        .filter((name) => DIRECTIONAL_ICONS.has(name))
        .map((name) => `${file}: ${name}`),
    );
    expect(directional).toEqual([]);

    expect(sources[TRANSCRIPT_TURNS]).toContain('dir="auto"');

    // The error callout's danger bar is a logical side now (item 25). jsdom's CSS engine
    // drops `border-inline-start`, so the styled source is the evidence here: the browser
    // resolves the same logical side to the reading direction's start, and no journey renders
    // the callout in an RTL locale to observe it.
    const callout = sources['/src/shared/ui/ErrorCallout.tsx'] ?? '';
    expect(callout).toContain(
      'border-inline-start: ${(props) => props.theme.borderWidths.ring} solid',
    );
    expect(callout).toContain('props.theme.colors.danger');
  });
});
