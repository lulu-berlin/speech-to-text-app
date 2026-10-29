// Roadmap item 79 (discovered): the device-probe spec must find whisper-cli on Windows, where there
// is no `which` and the executable carries an extension from PATHEXT. The lookup is PATH order, then
// the extensions — the same answer `which` gives on POSIX — and it answers the same on every host.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { commandDirectory } from './executable.ts';

// One row per host shape: the platform, its PATH, the extensions tried, the one candidate file that
// exists, and the directory the lookup must answer (or null).
const CASES: {
  reason: string;
  platform: NodeJS.Platform;
  pathValue: string;
  extensions: string[];
  existing: string | null;
  expected: string | null;
}[] = [
  {
    reason: 'Windows: the second entry wins, the first has no whisper-cli.EXE',
    platform: 'win32',
    pathValue: 'C:\\Windows;C:\\Program Files\\tools',
    extensions: ['.EXE', '.CMD'],
    existing: 'C:\\Program Files\\tools\\whisper-cli.EXE',
    expected: 'C:\\Program Files\\tools',
  },
  {
    reason: 'POSIX: the bare name in a bin directory',
    platform: 'linux',
    pathValue: '/usr/bin:/nix/store/x/bin',
    extensions: [''],
    existing: '/nix/store/x/bin/whisper-cli',
    expected: '/nix/store/x/bin',
  },
  {
    reason: 'an absent command is null, never a guessed directory',
    platform: 'linux',
    pathValue: '/usr/bin',
    extensions: [''],
    existing: null,
    expected: null,
  },
];

test('a command is found where the PATH holds it, with PATHEXT on Windows', () => {
  for (const { reason, expected, existing, ...lookup } of CASES) {
    assert.equal(
      commandDirectory('whisper-cli', { ...lookup, exists: (file) => file === existing }),
      expected,
      reason,
    );
  }
});
