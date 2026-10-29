// Roadmap item 79, test-list item 7: `gradlew.bat e2e` must fail with a clear message when the
// Gradle-managed pnpm is missing, instead of skipping or hanging. Gradle hands the launcher its
// pinned pnpm path; the launcher refuses a gap with the command that restores it.
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { requireManagedPnpm, runManagedPnpm, shellArgs, shellProgram } from './managed-pnpm.ts';
import type { PnpmProcess } from './managed-pnpm.ts';

test('a missing managed pnpm fails with the command that restores it', () => {
  const dir = mkdtempSync(join(tmpdir(), 'stt-e2e-pnpm-'));
  try {
    const missing = join(dir, 'pnpm');
    assert.throws(
      () => requireManagedPnpm(missing),
      (error: unknown) => {
        const message = (error as Error).message;
        assert.ok(message.includes(missing), `the message names the missing path: ${message}`);
        assert.match(message, /e2eInstall/, 'the message names the task that installs it');
        return true;
      },
    );

    // The binary the install produced is used as is.
    writeFileSync(missing, '');
    assert.equal(requireManagedPnpm(missing), missing);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('the launcher ends the way pnpm ended, and names a pnpm it cannot start', async () => {
  const run = async (
    emit: (child: EventEmitter) => void,
  ): Promise<{ ended: number[]; failures: string[] }> => {
    const child = new EventEmitter();
    const ended: number[] = [];
    const failures: string[] = [];
    runManagedPnpm('/pnpm', ['test'], {
      spawn: () => {
        setImmediate(() => emit(child));
        return child as unknown as PnpmProcess;
      },
      exit: (value) => ended.push(value),
      fail: (message) => failures.push(message),
    });
    await new Promise((resolve) => setImmediate(resolve));
    return { ended, failures };
  };

  assert.deepEqual((await run((child) => child.emit('exit', 3, null))).ended, [3]);
  // A process with no exit code is a failure, never a silent success.
  assert.deepEqual((await run((child) => child.emit('exit', null, null))).ended, [1]);
  const refused = await run((child) => child.emit('error', new Error('EACCES')));
  assert.deepEqual(refused.failures, ['could not start /pnpm: EACCES']);
  assert.deepEqual(refused.ended, []);
});

// Roadmap item 79, review finding 2: the launcher starts the managed pnpm through a shell on Windows, and
// Node hands the command line to cmd.exe as written — so a profile path with a space (C:\Users\John Doe)
// must arrive quoted, or cmd.exe starts "C:\Users\John" with the rest as arguments.
test('the launcher quotes a pnpm path that contains spaces for the shell', () => {
  const spaced = 'C:\\Users\\John Doe\\.gradle\\stt-app\\node\\pnpm\\pnpm-v11.27.0\\pnpm.cmd';
  assert.equal(shellProgram(spaced, 'win32'), `"${spaced}"`);

  // POSIX starts pnpm directly: a quote would become part of the file name.
  const direct = '/home/john/.gradle/stt-app/node/pnpm/pnpm-v11.27.0/bin/pnpm';
  assert.equal(shellProgram(direct, 'linux'), direct);

  // Arguments share the command line, so whitespace in one is quoted the same way.
  assert.deepEqual(shellArgs(['install', '--frozen-lockfile'], 'win32'), [
    'install',
    '--frozen-lockfile',
  ]);
  assert.deepEqual(shellArgs(['C:\\My Projects\\e2e'], 'win32'), ['"C:\\My Projects\\e2e"']);
  assert.deepEqual(shellArgs(['/a b/e2e'], 'linux'), ['/a b/e2e']);
});

// Roadmap item 79 (discovered, third review): cmd.exe reads `&`, `|`, `^`, `(` and `)` as syntax outside
// quotes, so a profile path like C:\Users\R&D\… breaks the launch even though it holds no whitespace, and a
// Windows path can never contain a quote — so the program is always quoted there.
test('the launcher quotes a pnpm path that holds cmd.exe special characters', () => {
  const special = 'C:\\Users\\R&D\\.gradle\\stt-app\\node\\pnpm\\pnpm-v11.27.0\\pnpm.cmd';
  assert.equal(shellProgram(special, 'win32'), `"${special}"`);

  // A path without whitespace or special characters keeps its quotes too: cmd.exe strips them.
  assert.equal(
    shellProgram('C:\\Users\\runner\\.gradle\\stt-app\\node\\pnpm\\pnpm.cmd', 'win32'),
    '"C:\\Users\\runner\\.gradle\\stt-app\\node\\pnpm\\pnpm.cmd"',
  );

  for (const argument of ['C:\\A&B\\e2e', 'C:\\A|B\\e2e', 'C:\\A^B\\e2e', 'C:\\A(B)\\e2e']) {
    assert.deepEqual(shellArgs([argument], 'win32'), [`"${argument}"`], argument);
  }

  // POSIX starts pnpm directly, so a path with the same characters arrives as written.
  assert.equal(shellProgram('/a&b/pnpm', 'linux'), '/a&b/pnpm');
  assert.deepEqual(shellArgs(['/a&b/e2e'], 'linux'), ['/a&b/e2e']);
});
