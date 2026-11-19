import 'styled-components';
import colorsFile from '../../../../ui-theme/colors.json';

// The five status groups (ADR 0033): pending = QUEUED, PAUSED; active = RUNNING;
// done = COMPLETED; problem = FAILED; gone = CANCELED.
export type StatusGroup = 'pending' | 'active' | 'done' | 'problem' | 'gone';

// Colors are plain #rrggbb so their contrast can be measured (AA in both sets).
export interface ThemeTokens {
  colors: {
    background: string;
    surface: string;
    surfaceRaised: string;
    text: string;
    textMuted: string;
    border: string;
    accent: string;
    accentText: string;
    // A light wash of the accent behind muted text (the drop zone while files are over
    // it, item 25); the same value as the active status background.
    accentTint: string;
    focusRing: string;
    danger: string;
    // The dimming layer under dialogs and menus (an alpha value; theme.ts may hold it).
    overlay: string;
    // The neutral that marks a control's shape and state (unchecked switch track, slider
    // rail and thumb border): at least 3:1 against every surface (WCAG 1.4.11).
    control: string;
    // Button state fills (primary and danger hover/active, secondary active; the base
    // fills are accent/surface/danger, secondary hover is surfaceRaised).
    buttonPrimaryHover: string;
    buttonPrimaryActive: string;
    buttonSecondaryActive: string;
    buttonDangerHover: string;
    buttonDangerActive: string;
    status: Record<StatusGroup, { background: string; text: string }>;
    // One tint per speaker index (item 12's 0-based speakerId), taken modulo the size; the
    // speaker's name is shown in it (item 15). Chosen for contrast, AA in both themes.
    speakerTints: readonly string[];
  };
  // The stenographer illustration (item 17): one entry per color of the artwork, all 32
  // collapses to 30 (skin and browns); the component renders every shape from these so the
  // mascot follows the theme. Decorative, so no contrast requirement is measured.
  illustration: {
    backdropFrom: string;
    backdropTo: string;
    desk: string;
    deskDeep: string;
    deskRim: string;
    chairBack: string;
    chairSeat: string;
    machineTop: string;
    machineFrom: string;
    machineTo: string;
    machineMetal: string;
    machineMetalSoft: string;
    machineAccent: string;
    key: string;
    keyFlash: string;
    ink: string;
    inkSoft: string;
    skin: string;
    skinShade: string;
    skinHighlight: string;
    accentPurple: string;
    accentPurpleSoft: string;
    accentPink: string;
    accentYellow: string;
    accentTeal: string;
    accentTealDeep: string;
    accentBlue: string;
    lampGlow: string;
    lampHot: string;
    cheek: string;
  };
  // Lengths are rem so they follow the reader's font size (no raw px outside the tokens;
  // even here there is none — see src/conventions.test.ts).
  spacing: {
    xs: string;
    sm: string;
    md: string;
    lg: string;
    xl: string;
    xxl: string;
  };
  borderWidths: {
    thin: string;
    ring: string;
  };
  sizes: {
    viewportMinHeight: string;
    pageMaxWidth: string;
    // The transcript's reading column (item 25): a measure of about 70 characters, in ch
    // so it follows the typeface; and the left gutter its turn start times sit in, wide
    // enough for h:mm:ss.
    readingMeasure: string;
    timeGutter: string;
    // The width of an uploading file's progress bar beside its filename (item 25).
    uploadBar: string;
    switchWidth: string;
    switchHeight: string;
    switchThumb: string;
    sliderThumb: string;
    // The stenographer artwork's sizes (item 17): the header's brand icon — a static mark
    // in the corner, 40 px tall (item 59) — and the empty-state illustration. The
    // compact header (item 59) is text-only, and its height is what the transcript's
    // scroll margin reserves; on a phone the header wraps, so its compact form is taller.
    stenoIcon: string;
    stenoEmpty: string;
    compactHeader: string;
    compactHeaderPhone: string;
    // The transcript's scroll region (item 15): tall enough to read, short enough that
    // the player stays on screen while the transcript follows playback.
    transcriptMaxHeight: string;
    listboxMaxHeight: string;
  };
  radii: {
    dialog: string;
    button: string;
    card: string;
    pill: string;
  };
  // Elevation, as a literal shadow value (theme.ts may hold such values).
  shadows: {
    card: string;
    // The stronger elevation of a surface lifted above its neighbours (the dragged queue
    // row, item 59).
    raised: string;
  };
  // Durations and easings for the built-in animations (theme.ts may hold such keywords).
  motion: {
    spinDuration: string;
    spinEasing: string;
    pulseDuration: string;
    pulseEasing: string;
    // A queue row sliding aside while another is dragged past it (item 25).
    slideDuration: string;
    slideEasing: string;
    // The navigation pill's hover and state transitions (item 31).
    navDuration: string;
    navEasing: string;
    // The sweep of an indeterminate progress segment while a running task has no
    // percentage yet (item 59).
    indeterminateDuration: string;
    indeterminateEasing: string;
  };
  // The type scale: sizes ascend, weights are the font's 400/500/600, line heights are
  // unitless (WCAG 1.4.12 asks for at least 1.5; the transcript reads more loosely).
  fontSizes: {
    small: string;
    body: string;
    title: string;
  };
  fontWeights: {
    regular: number;
    medium: number;
    semibold: number;
  };
  lineHeights: {
    body: number;
    reading: number;
  };
  // Viewport widths for media queries, in rem.
  breakpoints: {
    sm: string;
    md: string;
    lg: string;
  };
  iconSizes: {
    sm: string;
    md: string;
    lg: string;
  };
  fontFamilies: {
    sans: string;
  };
}

// Named spacing tokens; components take these keys rather than inventing values.
export type SpacingKey = keyof ThemeTokens['spacing'];

// Everything except colors is the same in both sets; only the color roles differ.
const baseTokens = {
  spacing: {
    xs: '0.25rem',
    sm: '0.5rem',
    md: '1rem',
    lg: '1.5rem',
    xl: '2rem',
    xxl: '3rem',
  },
  borderWidths: {
    thin: '0.0625rem',
    ring: '0.125rem',
  },
  sizes: {
    viewportMinHeight: '100vh',
    pageMaxWidth: '48rem',
    readingMeasure: '70ch',
    timeGutter: '8ch',
    uploadBar: '16rem',
    switchWidth: '2.75rem',
    switchHeight: '1.5rem',
    switchThumb: '1.25rem',
    sliderThumb: '1.25rem',
    // 40 px: the user's cap for the header mark (item 59).
    stenoIcon: '2.5rem',
    stenoEmpty: '8.75rem',
    // The text-only compact header: its content plus the smaller block padding. The
    // phone's wrapped form stacks the controls under the title.
    compactHeader: '3.5rem',
    compactHeaderPhone: '6.25rem',
    transcriptMaxHeight: '60vh',
    listboxMaxHeight: '16rem',
  },
  radii: {
    dialog: '0.5rem',
    button: '0.25rem',
    card: '0.5rem',
    pill: '62.5rem',
  },
  fontSizes: {
    small: '0.875rem',
    body: '1rem',
    title: '1.5rem',
  },
  fontWeights: {
    regular: 400,
    medium: 500,
    semibold: 600,
  },
  lineHeights: {
    body: 1.5,
    reading: 1.7,
  },
  breakpoints: {
    sm: '40rem',
    md: '48rem',
    lg: '64rem',
  },
  iconSizes: {
    sm: '1rem',
    md: '1.25rem',
    lg: '1.5rem',
  },
  // The self-hosted font first, the system stack as fallback (the file is in ./fonts).
  fontFamilies: {
    sans: '"IBM Plex Sans", system-ui, -apple-system, "Segoe UI", sans-serif',
  },
  motion: {
    spinDuration: '0.8s',
    spinEasing: 'linear',
    pulseDuration: '1.6s',
    pulseEasing: 'ease-in-out',
    slideDuration: '0.15s',
    slideEasing: 'ease-out',
    navDuration: '0.15s',
    navEasing: 'ease-out',
    indeterminateDuration: '1.2s',
    indeterminateEasing: 'ease-in-out',
  },
};

// The shared colour tokens (ADR 0068): `ui-theme/colors.json` holds one value per role and theme,
// and both themes take every colour from it, so the browser and the TUI cannot drift apart. A role
// missing from a theme fails the type check; the tokens test fails when the file and the themes
// disagree about which roles exist.
type Mode = 'light' | 'dark';

function colorsFor(mode: Mode): ThemeTokens['colors'] {
  const themed = colorsFile.colors;
  const status = (group: keyof typeof themed.status) => ({
    background: themed.status[group].background[mode],
    text: themed.status[group].text[mode],
  });
  return {
    background: themed.background[mode],
    surface: themed.surface[mode],
    surfaceRaised: themed.surfaceRaised[mode],
    text: themed.text[mode],
    textMuted: themed.textMuted[mode],
    border: themed.border[mode],
    accent: themed.accent[mode],
    accentText: themed.accentText[mode],
    accentTint: themed.accentTint[mode],
    focusRing: themed.focusRing[mode],
    danger: themed.danger[mode],
    overlay: themed.overlay[mode],
    control: themed.control[mode],
    buttonPrimaryHover: themed.buttonPrimaryHover[mode],
    buttonPrimaryActive: themed.buttonPrimaryActive[mode],
    buttonSecondaryActive: themed.buttonSecondaryActive[mode],
    buttonDangerHover: themed.buttonDangerHover[mode],
    buttonDangerActive: themed.buttonDangerActive[mode],
    status: {
      pending: status('pending'),
      active: status('active'),
      done: status('done'),
      problem: status('problem'),
      gone: status('gone'),
    },
    speakerTints: themed.speakerTints[mode],
  };
}

function illustrationFor(mode: Mode): ThemeTokens['illustration'] {
  const art = colorsFile.illustration;
  return {
    backdropFrom: art.backdropFrom[mode],
    backdropTo: art.backdropTo[mode],
    desk: art.desk[mode],
    deskDeep: art.deskDeep[mode],
    deskRim: art.deskRim[mode],
    chairBack: art.chairBack[mode],
    chairSeat: art.chairSeat[mode],
    machineTop: art.machineTop[mode],
    machineFrom: art.machineFrom[mode],
    machineTo: art.machineTo[mode],
    machineMetal: art.machineMetal[mode],
    machineMetalSoft: art.machineMetalSoft[mode],
    machineAccent: art.machineAccent[mode],
    key: art.key[mode],
    keyFlash: art.keyFlash[mode],
    ink: art.ink[mode],
    inkSoft: art.inkSoft[mode],
    skin: art.skin[mode],
    skinShade: art.skinShade[mode],
    skinHighlight: art.skinHighlight[mode],
    accentPurple: art.accentPurple[mode],
    accentPurpleSoft: art.accentPurpleSoft[mode],
    accentPink: art.accentPink[mode],
    accentYellow: art.accentYellow[mode],
    accentTeal: art.accentTeal[mode],
    accentTealDeep: art.accentTealDeep[mode],
    accentBlue: art.accentBlue[mode],
    lampGlow: art.lampGlow[mode],
    lampHot: art.lampHot[mode],
    cheek: art.cheek[mode],
  };
}

export const lightTheme: ThemeTokens = {
  ...baseTokens,
  colors: colorsFor('light'),
  illustration: illustrationFor('light'),
  shadows: {
    card: '0 0.0625rem 0.125rem rgba(16, 24, 40, 0.08)',
    raised: '0 0.25rem 0.75rem rgba(16, 24, 40, 0.12)',
  },
};

export const darkTheme: ThemeTokens = {
  ...baseTokens,
  colors: colorsFor('dark'),
  illustration: illustrationFor('dark'),
  shadows: {
    card: '0 0.0625rem 0.25rem rgba(0, 0, 0, 0.4)',
    raised: '0 0.5rem 1.5rem rgba(0, 0, 0, 0.55)',
  },
};

// The token set for the OS preference.
export function themeFor(prefersDark: boolean): ThemeTokens {
  return prefersDark ? darkTheme : lightTheme;
}

declare module 'styled-components' {
  export interface DefaultTheme extends ThemeTokens {}
}
