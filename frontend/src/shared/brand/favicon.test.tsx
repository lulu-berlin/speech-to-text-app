import { describe, expect, it } from 'vitest';

// File reads follow the convention check's pattern (import.meta.glob with ?raw): the
// favicon is a static derivative of the stenographer artwork — the page links it and the
// file is the same drawing without the style block (nothing animates).
const rawFiles = import.meta.glob<string>(['/index.html', '/src/shared/brand/favicon.svg'], {
  query: '?raw',
  import: 'default',
  eager: true,
});

describe('the favicon', () => {
  it('is the static stenographer artwork, linked from the page', () => {
    const html = rawFiles['/index.html'];
    expect(html).toBeDefined();
    const link = html!.match(/<link rel="icon"[^>]*href="([^"]+)"/);
    expect(link).toBeDefined();
    const href = link?.[1];
    expect(href).toBeDefined();

    // The linked file is committed and is the artwork's static frame.
    const file = href!.replace(/^\//, '');
    const svg = rawFiles[`/${file}`];
    expect(svg).toBeDefined();
    expect(svg).toContain('<svg');
    expect(svg).toContain('viewBox="0 0 400 400"');
    expect(svg).not.toContain('<style');
    expect(svg).not.toContain('animation');
  });
});
