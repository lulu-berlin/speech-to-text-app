// Host side of the packaged desktop app's e2e (roadmap item 50, ADR 0046). The test runs on
// a clean machine: a stock container (ubuntu:24.04, or alpine:3.20 for the bundle). The test
// tooling — node, this package's Playwright and Xvfb — comes from the host's /nix/store, mounted
// read-only, so nothing test-only is installed into that machine. The container user has the
// host's uid, so test results land in e2e/test-results as the host user's files.
//
// Three packages, one set of specs (ADR 0053):
//   pnpm --dir e2e test:desktop         the .deb (after `pnpm --dir desktop dist`): apt installs it
//                                       with the system libraries it declares, and nothing else
//   pnpm --dir e2e test:desktop:nix     the flake package (`nix build .#desktop`): the container
//                                       installs nothing at all — the app runs from the store mount,
//                                       its whole closure included
//   pnpm --dir e2e test:desktop:bundle  the single-file bundle (`nix build .#desktop-bundle`) on a
//                                       musl Alpine with only zstd and tar added: no Nix, no glibc,
//                                       none of the .deb's libraries
//   pnpm --dir e2e test:desktop:bundle:amd  the bundle on Ubuntu with the host's /dev/dri and no
//                                       GPU driver installed: the AMD GPU item (a machine with
//                                       an AMD GPU only; no CI runner has one)
//   pnpm --dir e2e test:desktop:bundle:nvidia  the bundle on Ubuntu with the host's /dev/nvidia*
//                                       and /dev/dri, and NVIDIA's user-space driver of the host's
//                                       version installed the way Ubuntu has it: the NVIDIA item
//                                       (a machine running NVIDIA's own kernel driver only)
// And one check of the .deb itself, no specs:
//   pnpm --dir e2e test:desktop:deb-floor  apt must refuse the .deb on Ubuntu 22.04 (glibc 2.35),
//                                       below its glibc floor of 2.38, naming libc6
// Run from the devenv shell after `pnpm --dir e2e install` (the floor check needs only Docker).
import { spawnSync } from 'node:child_process';
import {
  createWriteStream,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  realpathSync,
  renameSync,
  statSync,
} from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';

const e2eRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = join(e2eRoot, '..');

function fail(message: string): never {
  console.error(`run-in-docker: ${message}`);
  process.exit(1);
}

/** The machine the specs run on: its image, the shell for the script, and what Docker grants. */
interface Machine {
  image: string;
  shell: string;
  docker: string[];
  /** Creates the test user with the host's uid and starts the suite as that user. */
  runAsTester: string;
}

/** Ubuntu's test-user script; `extra` runs as root once the user exists. */
function ubuntuRunAsTester(extra: string): string {
  return `
# The image's own user (ubuntu, uid 1000) may hold the host's uid, so it is removed to free it.
# Whether there is one must be decided by the name: a substitution's status is its pipeline's last
# command ("cut", always 0), so testing it would run "userdel -r ''" on a host whose uid the image
# does not have - a CI runner's 1001 - and that exits 6, under "set -eu" stopping the container
# before anything is printed.
existing=$(getent passwd "$HOST_UID" | cut -d: -f1)
if [ -n "$existing" ]; then
  # "userdel -r" warns that the image's user has no mail spool; the warning stays visible, so a
  # removal that really fails is never silent.
  userdel -r "$existing"
fi
getent group "$HOST_GID" >/dev/null || groupadd -g "$HOST_GID" tester
useradd -m -u "$HOST_UID" -g "$HOST_GID" tester
${extra}
cd /e2e
exec setpriv --reuid="$HOST_UID" --regid="$HOST_GID" --init-groups \\
  env HOME=/home/tester "$XVFB_RUN" -a "$NODE" "$PLAYWRIGHT_CLI" test -c playwright.desktop.config.ts
`;
}

// Stock Ubuntu. Chromium's sandbox creates its namespaces, which Docker's default seccomp profile
// only allows to a container granted CAP_SYS_ADMIN (a host install needs nothing like it; the app
// stays sandboxed either way).
const ubuntu: Machine = {
  image: 'ubuntu:24.04',
  shell: 'bash',
  docker: ['--cap-add=SYS_ADMIN'],
  runAsTester: ubuntuRunAsTester(''),
};

// Docker's relaxations for the bundle's bubblewrap: pivot_root is absent from Docker's default
// seccomp profile even with CAP_SYS_ADMIN, bubblewrap needs a fully visible /proc to mount its
// own (Docker masks parts of it), and on an AppArmor host (Ubuntu, the CI runner) Docker confines
// the container with its `docker-default` profile, whose `deny mount` refuses bubblewrap's first
// mount ("bwrap: Failed to make / slave: Permission denied"); a host has none of these restrictions.
const bubblewrapInDocker = [
  '--security-opt',
  'seccomp=unconfined',
  '--security-opt',
  'systempaths=unconfined',
  '--security-opt',
  'apparmor=unconfined',
];

// Stock Ubuntu with the host's GPU device nodes and nothing GPU-related installed by default: for
// AMD the bundle has to bring the user-space driver. The test user joins the group of each render
// node — its gid is the host's, so it may have no name in the image — the way a desktop user is in
// `render`.
function ubuntuWithGpu(devices: string[]): Machine {
  return {
    image: 'ubuntu:24.04',
    shell: 'bash',
    docker: [...bubblewrapInDocker, ...devices.flatMap((device) => ['--device', device])],
    runAsTester: ubuntuRunAsTester(`
for node in /dev/dri/renderD*; do
  gid=$(stat -c %g "$node")
  group=$(getent group "$gid" | cut -d: -f1)
  if [ -z "$group" ]; then group="gpu$gid"; groupadd -g "$gid" "$group"; fi
  usermod -aG "$group" tester
done`),
  };
}

/** NVIDIA's own driver on the host: its version, and its installer in the harness's cache. */
interface NvidiaDriver {
  version: string;
  installer: string;
}

// The user-space libraries must match the host's NVIDIA kernel driver exactly — the reason the
// bundle borrows them instead of shipping them — so the container gets NVIDIA's own installer of
// that version, downloaded once. (Test-only and host-dependent, so it cannot be pinned in Nix.)
async function nvidiaDriver(): Promise<NvidiaDriver> {
  const versionFile = '/proc/driver/nvidia/version';
  if (!existsSync(versionFile))
    fail(`${versionFile} is missing: NVIDIA's kernel driver is not running`);
  const version = /\s(\d{3,}\.\d+(?:\.\d+)?)\s/.exec(readFileSync(versionFile, 'utf8'))?.[1];
  if (!version) fail(`no driver version in ${versionFile}`);
  const name = `NVIDIA-Linux-x86_64-${version}.run`;
  const dir = join(process.env.XDG_CACHE_HOME || join(homedir(), '.cache'), 'stt-e2e', 'nvidia');
  const installer = join(dir, name);
  if (!existsSync(installer)) {
    const url = `https://download.nvidia.com/XFree86/Linux-x86_64/${version}/${name}`;
    console.log(`run-in-docker: downloading ${url} once into ${dir}`);
    const response = await fetch(url);
    if (!response.ok || !response.body) fail(`${url} answered ${response.status}`);
    mkdirSync(dir, { recursive: true });
    await pipeline(response.body, createWriteStream(`${installer}.part`));
    renameSync(`${installer}.part`, installer);
  }
  return { version, installer };
}

/** The host's NVIDIA device nodes (/dev/nvidia0, /dev/nvidiactl, …), directories left out. */
function nvidiaDevices(): string[] {
  return readdirSync('/dev')
    .filter((name) => name.startsWith('nvidia'))
    .map((name) => join('/dev', name))
    .filter((path) => statSync(path).isCharacterDevice());
}

// Stock Alpine: musl and busybox, no glibc, bash or shadow tools; the bundle's machine.
// busybox `su` without -l keeps the environment and sets HOME to the tester's.
const alpine: Machine = {
  image: 'alpine:3.20',
  shell: 'sh',
  docker: bubblewrapInDocker,
  runAsTester: `
group=$(getent group "$HOST_GID" | cut -d: -f1)
if [ -z "$group" ]; then addgroup -g "$HOST_GID" tester; group=tester; fi
existing=$(getent passwd "$HOST_UID" | cut -d: -f1)
if [ -n "$existing" ]; then deluser "$existing"; fi
adduser -D -u "$HOST_UID" -G "$group" tester
cd /e2e
exec su tester -c "$XVFB_RUN -a $NODE $PLAYWRIGHT_CLI test -c playwright.desktop.config.ts"
`,
};

/** The app under test: the machine it runs on, what root installs first, and the command. */
interface PackagedApp {
  /** deb | nix | bundle — the specs read it as STT_DESKTOP_PACKAGE. */
  kind: string;
  label: string;
  machine: Machine;
  mounts: string[];
  install: string;
  command: string;
}

// The .deb: root installs it the way a user would (apt resolves the package's declared
// dependencies); Electron keeps its Chromium sandbox on through the .deb's setuid chrome-sandbox.
function debApp(): PackagedApp {
  const debDir = join(repoRoot, 'desktop', 'dist');
  const debs = existsSync(debDir) ? readdirSync(debDir).filter((f) => /_amd64\.deb$/.test(f)) : [];
  const [debName] = debs;
  if (debs.length !== 1 || !debName) {
    fail(
      `expected exactly one *_amd64.deb in ${debDir}, found ${debs.length} — build it with: pnpm --dir desktop dist`,
    );
  }
  return {
    kind: 'deb',
    label: debName,
    machine: ubuntu,
    mounts: ['-v', `${join(debDir, debName)}:/tmp/stt-app.deb:ro`],
    install: [
      'export DEBIAN_FRONTEND=noninteractive',
      'apt-get update -qq',
      'apt-get install -y -qq --no-install-recommends /tmp/stt-app.deb',
    ].join('\n'),
    command: '/usr/bin/stt-app',
  };
}

/** Builds a flake output on the host and returns its store path. */
function nixBuild(target: string): string {
  const build = spawnSync('nix', ['build', target, '--no-link', '--print-out-paths'], {
    cwd: repoRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'inherit'],
  });
  if (build.error) fail(`nix could not be started: ${build.error.message}`);
  if (build.status !== 0) fail(`nix build ${target} failed (exit ${build.status})`);
  const out = build.stdout.trim();
  if (!out.startsWith('/nix/store/')) fail(`nix build printed no store path: ${out}`);
  return out;
}

// The flake package, built on the host: its store path is already inside the container through
// the read-only /nix/store mount, so nothing is installed — the closure is the whole runtime.
function nixApp(): PackagedApp {
  const out = nixBuild('.#desktop');
  return {
    kind: 'nix',
    label: out,
    machine: ubuntu,
    mounts: [],
    install: '',
    command: `${out}/bin/stt-app`,
  };
}

// The bundle, built on the host: one file whose launcher unpacks the closure into the per-user
// cache and runs the app under bubblewrap with its own unpacked store bound at /nix — so the
// container's /nix/store mount, which carries the test tooling, is invisible to the app. On Alpine
// by default; with a GPU on Ubuntu with the host's device nodes: --amd-gpu passes /dev/dri and
// installs no driver (the bundle's own Vulkan driver must find it); --nvidia-gpu adds /dev/nvidia*
// and NVIDIA's user-space driver of the host's version (the bundle has to borrow it).
function bundleApp(gpu: string, nvidia: NvidiaDriver | undefined): PackagedApp {
  const out = nixBuild('.#desktop-bundle');
  const devices = nvidia ? [...nvidiaDevices(), '/dev/dri'] : ['/dev/dri'];
  return {
    kind: 'bundle',
    label: gpu ? `${out} (${gpu} GPU)` : out,
    machine: gpu ? ubuntuWithGpu(devices) : alpine,
    mounts: nvidia ? ['-v', `${nvidia.installer}:/opt/nvidia-driver.run:ro`] : [],
    // The launcher's own host requirements (README): sh, tar and zstd (xz only unpacks NVIDIA's
    // installer). NVIDIA's driver goes where Ubuntu's driver packages put it: the libraries in the
    // multiarch directory with their soname links (ldconfig), the installer's own Vulkan manifest,
    // which names libGLX_nvidia.so.0 without a path (checked for 595.71.05), and its EGL vendor
    // manifest for libglvnd — NVIDIA's Vulkan driver declines to start without its EGL part (first
    // run: "Could not get 'vkCreateInstance' via 'vk_icdGetInstanceProcAddr'"). Then the
    // fixture models move out of /nix/store: inside the bundle /nix is the bundle's own unpacked
    // store, so a /nix/store path from the harness does not exist for the app. The copies keep the
    // store basenames the first-run specs serve them under.
    install: [
      gpu
        ? `apt-get update -qq && apt-get install -y -qq --no-install-recommends zstd${nvidia ? ' xz-utils' : ''} >/dev/null`
        : 'apk add --no-cache zstd tar >/dev/null',
      ...(nvidia
        ? [
            'sh /opt/nvidia-driver.run --extract-only --target /tmp/nvidia-driver >/dev/null',
            `cp /tmp/nvidia-driver/*.so.${nvidia.version} /usr/lib/x86_64-linux-gnu/`,
            'ldconfig',
            'mkdir -p /usr/share/vulkan/icd.d',
            'cp /tmp/nvidia-driver/nvidia_icd.json /usr/share/vulkan/icd.d/',
            'mkdir -p /usr/share/glvnd/egl_vendor.d',
            'cp /tmp/nvidia-driver/10_nvidia.json /usr/share/glvnd/egl_vendor.d/',
          ]
        : []),
      'mkdir -p /tmp/stt-fixtures',
      'cp "$TEST_WHISPER_TINY_EN_MODEL" "$STT_SPEAKER_MODEL" /tmp/stt-fixtures/',
      'chmod -R a+rX /tmp/stt-fixtures',
      'export TEST_WHISPER_TINY_EN_MODEL="/tmp/stt-fixtures/$(basename "$TEST_WHISPER_TINY_EN_MODEL")"',
      'export STT_SPEAKER_MODEL="/tmp/stt-fixtures/$(basename "$STT_SPEAKER_MODEL")"',
    ].join('\n'),
    command: `${out}/bin/stt-app-bundle`,
  };
}

// The .deb's glibc floor (roadmap item 50, review 2026-10-15): the whisper-cli prebuild and its
// libggml-* need glibc 2.38, so apt has to refuse the .deb on an older distribution rather than
// install an app whose every transcription fails with "GLIBC_2.38 not found". Ubuntu 22.04 has
// 2.35. No -qq on the install: apt's refusal names the dependency, and that is the evidence.
if (process.argv.includes('--deb-floor')) {
  const deb = debApp();
  const image = 'ubuntu:22.04';
  console.log(`run-in-docker: installing ${deb.label} in ${image} (glibc 2.35), which must fail`);
  const install = spawnSync(
    'docker',
    [
      'run',
      '--rm',
      ...deb.mounts,
      image,
      'bash',
      '-c',
      'export DEBIAN_FRONTEND=noninteractive && apt-get update -qq && apt-get install -y --no-install-recommends /tmp/stt-app.deb',
    ],
    { encoding: 'utf8' },
  );
  if (install.error) fail(`docker could not be started: ${install.error.message}`);
  const output = `${install.stdout}${install.stderr}`;
  const tail = output.trimEnd().split('\n').slice(-15).join('\n');
  if (install.status === 0) {
    fail(`apt installed ${deb.label} on ${image}, below the glibc floor:\n${tail}`);
  }
  if (!/\blibc6\b/.test(output)) {
    fail(
      `apt refused ${deb.label} on ${image} (exit ${install.status}), but not naming libc6:\n${tail}`,
    );
  }
  console.log(`${tail}\nrun-in-docker: apt refused ${deb.label} on ${image}, naming libc6`);
  process.exit(0);
}

// The GPU the specs may expect: STT_DESKTOP_GPU, empty without one.
const gpu = process.argv.includes('--amd-gpu')
  ? 'amd'
  : process.argv.includes('--nvidia-gpu')
    ? 'nvidia'
    : '';
if (gpu && !process.argv.includes('--bundle')) fail(`--${gpu}-gpu is a bundle mode: add --bundle`);
const nvidia = gpu === 'nvidia' ? await nvidiaDriver() : undefined;
const app = process.argv.includes('--bundle')
  ? bundleApp(gpu, nvidia)
  : process.argv.includes('--nix')
    ? nixApp()
    : debApp();

// The fixtures and models the test reads, all store paths the devenv shell exports.
const passedEnv = ['TEST_JFK_WAV', 'TEST_WHISPER_TINY_EN_MODEL', 'STT_SPEAKER_MODEL'];
for (const name of passedEnv) {
  if (!process.env[name]?.startsWith('/nix/store/')) {
    fail(`${name} must be a /nix/store path — run from the devenv shell`);
  }
}

// The pinned models' sha256 (versions.json — the same file the packages ship): the first-run
// spec writes its fixture pins from them, so the app is verified against the repository's pins and
// not against a hash of the very file the test serves. The repository is not mounted into the
// container, so the values travel as environment.
const { models: pins } = JSON.parse(readFileSync(join(repoRoot, 'versions.json'), 'utf8')) as {
  models: Record<'whisperTinyEn' | 'speaker', { sha256?: string }>;
};
const pinEnv = {
  TEST_WHISPER_TINY_EN_SHA256: pins.whisperTinyEn.sha256,
  TEST_SPEAKER_SHA256: pins.speaker.sha256,
};
for (const [name, value] of Object.entries(pinEnv)) {
  if (!/^[0-9a-f]{64}$/.test(value ?? ''))
    fail(`${name} is not a sha256 in versions.json: ${value}`);
}
const xvfbRun = whichInStore('xvfb-run');
// Roadmap item 74: the window-class spec reads the running app's WM_CLASS with xprop, resolved
// from the devenv shell like the rest of the test tooling (it is not installed in the container).
const xprop = whichInStore('xprop');
const node = realpathSync(process.execPath);
if (!node.startsWith('/nix/store/')) fail(`node must come from /nix/store, got ${node}`);
const playwrightCli = join(e2eRoot, 'node_modules', '@playwright', 'test', 'cli.js');
if (!existsSync(playwrightCli)) fail('e2e dependencies are missing — pnpm --dir e2e install');

// Inside the container: root prepares the app (if the package needs installing), then the test
// runs as an ordinary user with the host's uid.
const containerScript = `
set -eu
${app.install}
${app.machine.runAsTester}`;

const args = [
  'run',
  '--rm',
  ...app.machine.docker,
  // The exact-model-host spec (14) needs a githubusercontent.com host other than release-assets that
  // leads back to its own loopback fixture: a hop the app wrongly follows is then a counted request,
  // and nothing reaches GitHub.
  '--add-host',
  'raw.githubusercontent.com:127.0.0.1',
  '-v',
  '/nix/store:/nix/store:ro',
  '-v',
  `${e2eRoot}:/e2e`,
  ...app.mounts,
  '-e',
  `HOST_UID=${process.getuid?.()}`,
  '-e',
  `HOST_GID=${process.getgid?.()}`,
  '-e',
  `XVFB_RUN=${xvfbRun}`,
  '-e',
  `NODE=${node}`,
  '-e',
  'PLAYWRIGHT_CLI=/e2e/node_modules/@playwright/test/cli.js',
  '-e',
  `STT_DESKTOP_APP=${app.command}`,
  '-e',
  `STT_DESKTOP_PACKAGE=${app.kind}`,
  '-e',
  `STT_DESKTOP_GPU=${gpu}`,
  '-e',
  `STT_XPROP=${xprop}`,
  ...passedEnv.flatMap((name) => ['-e', `${name}=${process.env[name]}`]),
  ...Object.entries(pinEnv).flatMap(([name, value]) => ['-e', `${name}=${value}`]),
  app.machine.image,
  app.machine.shell,
  '-c',
  containerScript,
];
console.log(`run-in-docker: running the desktop e2e in ${app.machine.image} against ${app.label}`);
const result = spawnSync('docker', args, { stdio: 'inherit' });
if (result.error) fail(`docker could not be started: ${result.error.message}`);
process.exit(result.status ?? 1);

function whichInStore(command: string): string {
  for (const dir of (process.env.PATH ?? '').split(':')) {
    const candidate = join(dir, command);
    if (existsSync(candidate)) {
      const real = realpathSync(candidate);
      if (real.startsWith('/nix/store/')) return real;
    }
  }
  return fail(`${command} from /nix/store is not on PATH — re-enter the devenv shell (devenv.nix)`);
}
