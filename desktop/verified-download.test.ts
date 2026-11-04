// Roadmap item 90 (test-list item 10): an unsupported host fails fast, naming the supported
// platforms, instead of resolving a missing download. The desktop staging maps the OS/arch to a
// Nix system name and checks it against versions.json before any pin lookup; the Gradle bootstrap
// checks the same list before its task graph runs.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';
import { platformKey } from './verified-download.ts';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const supported = 'x86_64-linux, aarch64-darwin, x86_64-windows';

function pinFailure(platform: string): { status: number | null; stderr: string } {
  const module = pathToFileURL(join(here, 'verified-download.ts')).href;
  const script = `import { pin } from ${JSON.stringify(module)}; pin('whisper-cli', 'test', ${JSON.stringify(platform)});`;
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', script], {
    encoding: 'utf8',
  });
  return { status: result.status, stderr: result.stderr };
}

function gradleCommand(): string[] {
  const override = process.env.STT_GRADLE;
  if (override) return [override];
  const wrapper = join(root, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  assert.ok(existsSync(wrapper), `no Gradle wrapper at ${wrapper}`);
  return process.platform === 'win32' ? ['cmd', '/c', wrapper] : [wrapper];
}

test('the desktop staging maps every OS/arch to a Nix system name', () => {
  assert.equal(platformKey('darwin', 'arm64'), 'aarch64-darwin');
  assert.equal(platformKey('darwin', 'x64'), 'x86_64-darwin');
  assert.equal(platformKey('win32', 'x64'), 'x86_64-windows');
  assert.equal(platformKey('win32', 'arm64'), 'aarch64-windows');
  assert.equal(platformKey('linux', 'x64'), 'x86_64-linux');
  assert.equal(platformKey('linux', 'arm64'), 'aarch64-linux');
});

for (const platform of ['x86_64-darwin', 'aarch64-windows', 'aarch64-linux']) {
  test(`the desktop staging refuses ${platform} before a pin lookup`, () => {
    const result = pinFailure(platform);
    assert.equal(result.status, 1, `expected exit 1 for ${platform}, got ${result.status}`);
    assert.ok(result.stderr.includes(`unsupported platform ${platform}`), result.stderr);
    assert.ok(result.stderr.includes(supported), result.stderr);
  });
}

test(
  'the Gradle bootstrap refuses an unsupported host before any task runs',
  {
    // The bootstrap's check is host-independent; the Linux leg proves it without making macOS and
    // Windows jobs start a nested Gradle build for a host they cannot be.
    skip: process.platform === 'linux' ? false : 'proven on the Linux leg',
  },
  () => {
    // Windows ARM64 takes the same null-platform path as Linux ARM64, but faking os.name=Windows
    // makes the node plugin look for powershell before the task graph exists; the desktop test above
    // covers that host's mapping and refusal.
    const hosts = [
      { osName: 'macOS', osArch: 'x86_64', platform: 'x86_64-darwin' },
      { osName: 'Linux', osArch: 'aarch64', platform: 'aarch64-linux' },
    ];
    const [command, ...prefix] = gradleCommand();
    assert.ok(command, 'the Gradle command is empty');
    for (const host of hosts) {
      const result = spawnSync(
        command,
        [
          ...prefix,
          '--no-daemon',
          '-q',
          `-Dos.name=${host.osName}`,
          `-Dos.arch=${host.osArch}`,
          'prepareRuntime',
          '--dry-run',
        ],
        { cwd: root, encoding: 'utf8', timeout: 180_000 },
      );
      const output = `${result.stdout}\n${result.stderr}`;
      assert.notEqual(result.status, 0, `Gradle accepted ${host.platform}:\n${output}`);
      assert.match(output, /does not support this platform/);
      assert.ok(output.includes(`Supported: ${supported}`), output);
    }
  },
);
