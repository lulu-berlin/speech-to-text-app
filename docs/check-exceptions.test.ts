// The exception-policy check's own tests (roadmap item 60): a planted swallow must fail it, each
// allowed shape and the clean tree must pass. Run with `node --test docs/check-exceptions.test.ts`
// from the repository root (Node 24 strips the types).
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const repoRoot = fileURLToPath(new URL('..', import.meta.url));
const scriptPath = fileURLToPath(new URL('./check-exceptions.ts', import.meta.url));

interface Outcome {
  status: number;
  output: string;
}

function runCheck(args: string[]): Outcome {
  try {
    const output = execFileSync(process.execPath, [scriptPath, ...args], {
      cwd: repoRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return { status: 0, output };
  } catch (error) {
    const failure = error as { status?: number; stdout?: string; stderr?: string };
    return {
      status: failure.status ?? -1,
      output: `${failure.stdout ?? ''}${failure.stderr ?? ''}`,
    };
  }
}

// A temporary production tree with [files] (relative path -> Kotlin source).
function tree(files: Record<string, string>): string {
  const root = mkdtempSync(join(tmpdir(), 'stt-exceptions-'));
  for (const [path, source] of Object.entries(files)) {
    const file = join(root, path);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, source);
  }
  return root;
}

test('a broad catch that neither rethrows nor logs the throwable fails', () => {
  const root = tree({
    'Swallow.kt': `
fun load() {
    try {
        read()
    } catch (e: Exception) {
        println(e)
    }
}
`,
  });
  try {
    const outcome = runCheck([root]);
    assert.equal(outcome.status, 1);
    assert.match(outcome.output, /Swallow\.kt:5:/);
    assert.match(outcome.output, /swallow/i);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a catch that rethrows its throwable passes', () => {
  const root = tree({
    'Rethrow.kt': `
fun load() {
    try {
        read()
    } catch (e: Throwable) {
        throw IllegalStateException("cannot load", e)
    }
}
`,
  });
  try {
    assert.equal(runCheck([root]).status, 0);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a catch that logs its throwable passes, including through a named logger', () => {
  const root = tree({
    'Logged.kt': `
fun load() {
    try {
        read()
    } catch (e: Exception) {
        logger.warn("could not read", e)
    }
}
`,
  });
  try {
    assert.equal(runCheck([root]).status, 0);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a catch that logs only the throwable’s text fails', () => {
  const root = tree({
    'MessageOnly.kt': `
fun load() {
    try {
        read()
    } catch (e: Exception) {
        logger.warn("failed: {}", e.toString())
    }
}
`,
  });
  try {
    const outcome = runCheck([root]);
    assert.equal(outcome.status, 1);
    assert.match(outcome.output, /MessageOnly\.kt:5:/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a throw without the original throwable fails', () => {
  const root = tree({
    'CauseLess.kt': `
fun load() {
    try {
        read()
    } catch (e: Exception) {
        throw IllegalStateException("failed")
    }
}
`,
  });
  try {
    const outcome = runCheck([root]);
    assert.equal(outcome.status, 1);
    assert.match(outcome.output, /CauseLess\.kt:5:/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a marked deliberate degradation passes', () => {
  const root = tree({
    'Marked.kt': `
fun load() {
    try {
        read()
    } catch (e: Exception) {
        // exceptions: swallow-ok best-effort cleanup, the startup sweep retries it
    }
}
`,
  });
  try {
    assert.equal(runCheck([root]).status, 0);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('runCatching in production code fails', () => {
  const root = tree({
    'RunCatching.kt': `
fun load() {
    runCatching { read() }
}
`,
  });
  try {
    const outcome = runCheck([root]);
    assert.equal(outcome.status, 1);
    assert.match(outcome.output, /RunCatching\.kt:3:/);
    assert.match(outcome.output, /runCatching/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('a narrow catch is not the check’s business', () => {
  const root = tree({
    'Narrow.kt': `
fun load() {
    try {
        read()
    } catch (e: IOException) {
        return
    }
}
`,
  });
  try {
    assert.equal(runCheck([root]).status, 0);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('the production tree is clean', () => {
  const outcome = runCheck([]);
  assert.equal(outcome.status, 0, outcome.output);
});
