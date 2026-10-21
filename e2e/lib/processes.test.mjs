import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { ancestors, findProcess, processes } from './processes.mjs';

// The shape of `ps -axo pid=,ppid=,command=` on macOS: padded numeric columns, then the command.
const PS = `  735     1 /sbin/launchd
 4812   735 java -cp lib/* stt.MainKt /tmp/stt-data
 4900  4812 /opt/homebrew/bin/whisper-cli -m model -f /dev/null
`;

test('the ps fallback finds the app JVM and walks its ancestors', () => {
  const rows = processes({ psOutput: () => PS });

  const jvm = findProcess(rows, ['stt.MainKt', '/tmp/stt-data']);
  assert.equal(jvm?.pid, 4812);
  assert.deepEqual(ancestors(rows, jvm.pid), [4812, 735]);
  assert.equal(findProcess(rows, ['stt.MainKt', '/other-data']), null);
});

test('the /proc path reads the same rows', () => {
  const procRoot = mkdtempSync(join(tmpdir(), 'stt-proc-'));
  writeProc(procRoot, 4812, 735, 'java\0-cp\0lib/*\0stt.MainKt\0/tmp/stt-data\0');
  writeProc(procRoot, 735, 1, 'launchd\0');
  writeProc(procRoot, 1, 0, 'init\0');
  mkdirSync(join(procRoot, 'self'), { recursive: true });
  writeFileSync(join(procRoot, 'self', 'stat'), '1 (init) S 0 1 1 0 -1\n');

  const rows = processes({ procRoot });

  assert.equal(findProcess(rows, ['stt.MainKt'])?.pid, 4812);
  assert.deepEqual(ancestors(rows, 4812), [4812, 735]);
});

function writeProc(root, pid, ppid, cmdline) {
  mkdirSync(join(root, String(pid)), { recursive: true });
  writeFileSync(join(root, String(pid), 'cmdline'), cmdline);
  writeFileSync(join(root, String(pid), 'stat'), `${pid} (process) S ${ppid} 1 1 0 -1\n`);
}
