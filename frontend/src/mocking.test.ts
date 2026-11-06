// The migration's end state (roadmap item 91): MSW 3's own entrypoints only. The root package
// no longer carries the handlers (they live in msw/http), the 2.x option is gone, the pin is
// exact, and `msw init` still finds the committed worker through `msw.workerDirectory`.
import { readFileSync, realpathSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

interface PackageManifest {
  devDependencies: Record<string, string>;
  msw?: { workerDirectory?: string[] };
}

const packageJson = JSON.parse(
  readFileSync(join(process.cwd(), 'package.json'), 'utf8'),
) as PackageManifest;

// Every source file's text, keyed by its path from the project root.
const sources = import.meta.glob<string>('/src/**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
});

// This file names the patterns it looks for, so it is not one of the files it checks.
const SELF = '/src/mocking.test.ts';
const files = Object.entries(sources).filter(([file]) => file !== SELF);

describe('the MSW 3 setup', () => {
  it('imports from msw/http, never the package root, and keeps no 2.x option', () => {
    expect(files.filter(([, text]) => /from ['"]msw['"]/.test(text)).map(([file]) => file)).toEqual(
      [],
    );
    expect(
      files.filter(([, text]) => text.includes('onUnhandledRequest')).map(([file]) => file),
    ).toEqual([]);
  });

  it('pins msw exactly at 3.x and keeps the worker directory msw init writes to', () => {
    expect(packageJson.devDependencies.msw).toMatch(/^3\.\d+\.\d+$/);
    expect(packageJson.msw?.workerDirectory).toEqual(['mock']);
  });

  it('uses the @mswjs/interceptors copy msw itself resolves', () => {
    const fromProject = createRequire(join(process.cwd(), 'package.json'));
    const ours = realpathSync(fromProject.resolve('@mswjs/interceptors'));
    // The copy the handlers run through: msw's own dependency, resolved from its package.
    const fromMsw = realpathSync(
      createRequire(fromProject.resolve('msw/package.json')).resolve('@mswjs/interceptors'),
    );

    // Two copies install side by side when the pins drift; the web interceptors then are not
    // the classes InterceptorSource knows, in type and at run time.
    expect(ours).toBe(fromMsw);
  });
});
