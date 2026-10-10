import 'styled-components';
import { darkSpeakerTints, lightSpeakerTints } from './speakerPalette';

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
    switchWidth: string;
    switchHeight: string;
    switchThumb: string;
    sliderThumb: string;
    // The stenographer animation's sizes (item 17): the header instance and the
    // empty-state instance. The artwork is cropped to its drawing (no margin), so the
    // header reads clearly at 120 px; the empty-state instance is 140 px.
    stenoHeader: string;
    stenoEmpty: string;
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
  };
  // Durations and easings for the built-in animations (theme.ts may hold such keywords).
  motion: {
    spinDuration: string;
    spinEasing: string;
    pulseDuration: string;
    pulseEasing: string;
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
    timeGutter: '7ch',
    switchWidth: '2.75rem',
    switchHeight: '1.5rem',
    switchThumb: '1.25rem',
    sliderThumb: '1.25rem',
    stenoHeader: '7.5rem',
    stenoEmpty: '8.75rem',
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
  },
};

export const lightTheme: ThemeTokens = {
  ...baseTokens,
  colors: {
    background: '#ffffff',
    surface: '#f6f7f9',
    surfaceRaised: '#ffffff',
    text: '#1c1c1c',
    textMuted: '#5a616c',
    border: '#d7d7d7',
    accent: '#0057a8',
    accentText: '#ffffff',
    focusRing: '#0057a8',
    danger: '#b42318',
    overlay: 'rgba(16, 24, 40, 0.4)',
    control: '#7c838e',
    buttonPrimaryHover: '#004b94',
    buttonPrimaryActive: '#00407f',
    buttonSecondaryActive: '#e8ecf1',
    buttonDangerHover: '#9c1e15',
    buttonDangerActive: '#861a12',
    status: {
      pending: { background: '#eceef1', text: '#3d434c' },
      active: { background: '#e3eefa', text: '#0057a8' },
      done: { background: '#e3f4e8', text: '#1d6436' },
      problem: { background: '#fcebe9', text: '#b42318' },
      gone: { background: '#f3f3f3', text: '#5a616c' },
    },
    speakerTints: lightSpeakerTints,
  },
  illustration: {
    backdropFrom: '#FFF6E9',
    backdropTo: '#FFDDE8',
    desk: '#C98B5E',
    deskDeep: '#A86E45',
    deskRim: '#DDA477',
    chairBack: '#E17055',
    chairSeat: '#F0846A',
    machineTop: '#5A6B85',
    machineFrom: '#4A5A75',
    machineTo: '#323F55',
    machineMetal: '#9AA9B4',
    machineMetalSoft: '#B2BEC3',
    machineAccent: '#55EFC4',
    key: '#F5F6FA',
    keyFlash: '#C9D3DC',
    ink: '#2D1B3D',
    inkSoft: '#4A3560',
    skin: '#E8A87C',
    skinShade: '#CC8962',
    skinHighlight: '#F7D6C0',
    accentPurple: '#6C5CE7',
    accentPurpleSoft: '#A29BFE',
    accentPink: '#E84393',
    accentYellow: '#FDCB6E',
    accentTeal: '#00B894',
    accentTealDeep: '#00A383',
    accentBlue: '#0984E3',
    lampGlow: '#FFEAA7',
    lampHot: '#FFF6D6',
    cheek: '#FF7675',
  },
  shadows: {
    card: '0 0.0625rem 0.125rem rgba(16, 24, 40, 0.08)',
  },
};

export const darkTheme: ThemeTokens = {
  ...baseTokens,
  colors: {
    background: '#14161a',
    surface: '#1b1e23',
    surfaceRaised: '#23272e',
    text: '#e8e8e8',
    textMuted: '#a4acb8',
    border: '#3a3f47',
    accent: '#7fb2e5',
    accentText: '#14161a',
    focusRing: '#7fb2e5',
    danger: '#f28b82',
    overlay: 'rgba(0, 0, 0, 0.5)',
    control: '#7e8794',
    buttonPrimaryHover: '#93bceb',
    buttonPrimaryActive: '#a4c8f0',
    buttonSecondaryActive: '#242a31',
    buttonDangerHover: '#f49c94',
    buttonDangerActive: '#f6b3ac',
    status: {
      pending: { background: '#2a2e35', text: '#cdd2da' },
      active: { background: '#1a2a3c', text: '#7fb2e5' },
      done: { background: '#18291f', text: '#7fcf9c' },
      problem: { background: '#3a1c1c', text: '#f28b82' },
      gone: { background: '#22252a', text: '#9aa2ad' },
    },
    speakerTints: darkSpeakerTints,
  },
  illustration: {
    // The backdrop follows the surface family so the piece sits in dark mode (item 17).
    backdropFrom: '#23272e',
    backdropTo: '#1b1e23',
    desk: '#8a5a38',
    deskDeep: '#6e4526',
    deskRim: '#9c6c42',
    chairBack: '#b0503f',
    chairSeat: '#cd6b54',
    machineTop: '#45536c',
    machineFrom: '#3b4860',
    machineTo: '#2e3950',
    machineMetal: '#798690',
    machineMetalSoft: '#8b98a2',
    machineAccent: '#3fd9b0',
    key: '#d9dee6',
    keyFlash: '#97a3b0',
    // The ink sits on the dark backdrop, so the hair lifts instead of merging with it.
    ink: '#45355f',
    inkSoft: '#6a5790',
    skin: '#f0b48c',
    skinShade: '#cf8a5e',
    skinHighlight: '#ffdcc6',
    accentPurple: '#8f7fed',
    accentPurpleSoft: '#b3a9ff',
    accentPink: '#f0579f',
    accentYellow: '#ffd485',
    accentTeal: '#26c99c',
    accentTealDeep: '#1fb58c',
    accentBlue: '#3f9df5',
    // The lamp's warm light stays warm in both themes.
    lampGlow: '#FFEAA7',
    lampHot: '#FFF6D6',
    cheek: '#ff8a87',
  },
  shadows: {
    card: '0 0.0625rem 0.25rem rgba(0, 0, 0, 0.4)',
  },
};

// The token set for the OS preference.
export function themeFor(prefersDark: boolean): ThemeTokens {
  return prefersDark ? darkTheme : lightTheme;
}

declare module 'styled-components' {
  export interface DefaultTheme extends ThemeTokens {}
}
