import { type Locator, type Page } from '@playwright/test';

// The transcript's segments: every element with the button role except the current
// segment's controls, which carry an aria-label (item 52) where segments do not.
export const SEGMENTS = '[role="button"]:not([aria-label])';

// The index of the highlighted segment among the transcript's segment buttons (-1: none).
export async function activeIndex(region: Locator): Promise<number> {
  return region.evaluate(
    (element, segments) =>
      [...element.querySelectorAll(segments)].findIndex((segment) =>
        segment.hasAttribute('data-active'),
      ),
    SEGMENTS,
  );
}

// Whether the highlighted segment lies inside the transcript region's visible box.
export async function activeInView(region: Locator): Promise<boolean> {
  return region.evaluate((element) => {
    const active = element.querySelector('[data-active]');
    if (active === null) {
      return false;
    }
    const box = element.getBoundingClientRect();
    const segment = active.getBoundingClientRect();
    return segment.top >= box.top - 1 && segment.bottom <= box.bottom + 1;
  });
}

// The audio element's currentTime in seconds: the playhead's own position, read from the element
// rather than from a display that may render it a timeupdate later.
export async function audioSecond(page: Page): Promise<number> {
  return page.locator('audio').evaluate((audio: HTMLAudioElement) => audio.currentTime);
}
