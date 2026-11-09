// The pin-source check (roadmap item 97): a pin the audit found declared twice has one source
// again, or is held equal by this check. `versions.json` is the rendered manifest; the Nix files
// and the build scripts must not restate a value it already carries.
//
//   node docs/check-pins.ts [root]     (default: .)
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { commandsOf, parseJobs, stepsOf } from './ci-workflow.ts';

interface Download {
  url: string;
  sha256: string;
}

interface Manifest {
  downloads: Record<string, Record<string, Download>>;
  ffmpegAsset?: string;
  jdk?: string;
  models?: Record<string, { sha256: string }>;
}

const root = process.argv[2] ?? '.';
const read = (path: string): string => readFileSync(join(root, path), 'utf8');

const failures: string[] = [];
const manifest = JSON.parse(read('versions.json')) as Manifest;
const versionsNix = read('nix/versions.nix');
const artifactsNix = read('nix/desktop-artifacts.nix');

// The darwin artifacts are the Gradle bootstrap's own downloads (nix/versions.nix), so the Nix
// prebuilt pins read them instead of restating the URL and sha256. The audit found both copies and
// no comparison of their sha256s.
const darwinPins = [
  { tool: 'whisper-cli', attr: 'whisperCpp', source: '"whisper-cli"."aarch64-darwin"' },
  { tool: 'ffmpeg', attr: 'ffmpeg', source: 'ffmpeg."aarch64-darwin"' },
] as const;
for (const { tool, attr, source } of darwinPins) {
  const pin = manifest.downloads[tool]?.['aarch64-darwin'];
  if (pin === undefined) {
    failures.push(`versions.json has no ${tool} download for aarch64-darwin`);
    continue;
  }
  const inVersions = versionsNix.split(pin.sha256).length - 1;
  const inArtifacts = artifactsNix.split(pin.sha256).length - 1;
  if (inVersions !== 1) {
    failures.push(
      `nix/versions.nix must hold the ${tool} aarch64-darwin sha256 exactly once; found ${inVersions}`,
    );
  }
  if (inArtifacts !== 0) {
    failures.push(
      `nix/desktop-artifacts.nix repeats the ${tool} aarch64-darwin sha256; it must read nix/versions.nix`,
    );
  }
  if (!artifactsNix.includes(`versions.downloads.${source}`)) {
    failures.push(
      `nix/desktop-artifacts.nix's ${attr}.aarch64-darwin pin must read versions.downloads.${source}`,
    );
  }
}
if (!artifactsNix.includes('import ./versions.nix')) {
  failures.push('nix/desktop-artifacts.nix must import ./versions.nix for the darwin pins');
}

// The audio-only ffmpeg's release version: nix/versions.nix declares it once, the flake renders it
// into versions.json, and both the Gradle bootstrap and the Windows asset job read it there. The
// version is the release tag and the file-name part of every audio-only asset.
const buildGradle = read('build.gradle.kts');
const workflow = parseJobs(read('.github/workflows/ci.yml'));
const ffmpegAsset = manifest.ffmpegAsset;
if (ffmpegAsset === undefined) {
  failures.push('versions.json has no ffmpegAsset (the audio-only release version)');
} else {
  if (!/^\d+\.\d+\.\d+$/.test(ffmpegAsset)) {
    failures.push(`versions.json's ffmpegAsset is not x.y.z: ${ffmpegAsset}`);
  }
  if (!buildGradle.includes('manifest["ffmpegAsset"]')) {
    failures.push('build.gradle.kts must read the ffmpeg asset version from versions.json');
  }
  if (buildGradle.includes(ffmpegAsset)) {
    failures.push(`build.gradle.kts hardcodes the ffmpeg asset version ${ffmpegAsset}`);
  }
  const assetJob = workflow.get('windows-ffmpeg-asset');
  if (assetJob === undefined) {
    failures.push('the windows-ffmpeg-asset job is missing');
  } else {
    const commands = stepsOf(assetJob).map(commandsOf);
    if (!commands.some((command) => command.includes('.ffmpegAsset'))) {
      failures.push('windows-ffmpeg-asset must read .ffmpegAsset from versions.json');
    }
    if (commands.some((command) => command.includes(ffmpegAsset))) {
      failures.push(`windows-ffmpeg-asset hardcodes the ffmpeg asset version ${ffmpegAsset}`);
    }
  }
  if (!versionsNix.includes(`ffmpegAsset = "${ffmpegAsset}"`)) {
    failures.push(`nix/versions.nix must declare ffmpegAsset = "${ffmpegAsset}" once`);
  }
  // Declaring it in the `let` is not enough: the flake and nix/desktop-artifacts.nix read it off
  // the imported attrset, so it must be exported there too.
  if (!/^\s*inherit ffmpegAsset;$/m.test(versionsNix)) {
    failures.push(
      'nix/versions.nix must export ffmpegAsset in its returned attribute set (inherit ffmpegAsset;)',
    );
  }
  if (!artifactsNix.includes('/download/ffmpeg-${versions.ffmpegAsset}/')) {
    failures.push('nix/desktop-artifacts.nix must read versions.ffmpegAsset in its release URLs');
  }
}

// The three joined pin pairs from the 2026-11-06 audit: enterTest's model hashes come from
// nix/models.nix, the frontend build reads the root's node/pnpm home instead of rebuilding it, and
// every Temurin download names the manifest's jdk in both the release tag and the file name.
const devenv = read('devenv.nix');
const frontendBuild = read('frontend/build.gradle.kts');
const modelRefs = [
  ['whisper', 'models.meta.whisper.sha256'],
  ['speaker', 'models.meta.speaker.sha256'],
] as const;
for (const [model, ref] of modelRefs) {
  const sha256 = manifest.models?.[model]?.sha256;
  if (sha256 === undefined) {
    failures.push(`versions.json has no models.${model}.sha256`);
    continue;
  }
  if (devenv.includes(sha256)) {
    failures.push(`devenv.nix hardcodes the ${model} model sha256; it must read ${ref}`);
  }
  if (!devenv.includes(ref)) {
    failures.push(`devenv.nix must read ${ref}`);
  }
}
if (!buildGradle.includes('extra["sttNodeHome"]')) {
  failures.push('build.gradle.kts must define sttNodeHome once for the frontend build');
}
if (!frontendBuild.includes('rootProject.extra["sttNodeHome"]')) {
  failures.push("frontend/build.gradle.kts must read the root's sttNodeHome");
}
if (frontendBuild.includes('stt-app/node')) {
  failures.push('frontend/build.gradle.kts rebuilds the stt-app/node path instead of sharing it');
}
if (manifest.jdk === undefined) {
  failures.push('versions.json has no jdk');
} else {
  const jdk = manifest.jdk;
  const tag = `jdk-${encodeURIComponent(jdk)}/`;
  const file = `_${jdk.replace('+', '_')}.`;
  for (const [platform, pin] of Object.entries(manifest.downloads['temurin-jdk'] ?? {})) {
    if (!pin.url.includes(tag) || !pin.url.includes(file)) {
      failures.push(
        `versions.json's temurin-jdk.${platform} url does not name the manifest's jdk ${jdk}`,
      );
    }
  }
}

if (failures.length > 0) {
  console.error('Pin sources drifted:');
  for (const failure of failures) {
    console.error(`  ${failure}`);
  }
  process.exit(1);
}
console.log('Every joined pin has one source.');
