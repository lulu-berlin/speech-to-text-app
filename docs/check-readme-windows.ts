// Checks that README.md documents the Windows path (roadmap item 49, test-list item 9): the
// ways-to-run table says Windows x64 is supported, a Windows section exists, and the section itself
// names the bootstrap commands, the Vulkan driver requirement, the CPU fallback, the per-user data
// directory and the x64-only scope. The section ends at the next heading, so a fact can only match
// inside it. The prose itself is read by the user; this pin keeps those facts from being dropped
// silently. Exits 1 on any missing fact.
//
//   node docs/check-readme-windows.ts
import { readFileSync } from 'node:fs';

const readme = readFileSync(new URL('../README.md', import.meta.url), 'utf8');

const heading = /^#{3,4} Windows x64$/m.exec(readme);
const section =
  heading === null
    ? ''
    : (readme.slice(heading.index + heading[0].length).split(/^#{2,4} /m)[0] ?? '');

const facts: [what: string, pattern: RegExp, text: string][] = [
  [
    'a Windows x64 entry in the ways-to-run table',
    /\[Gradle without Nix\]\(#gradle-without-nix\)[^\n]*Windows x64/,
    readme,
  ],
  ['a Windows section', /^#{3,4} Windows x64$/m, readme],
  ['the Gradle bootstrap command', /\bgradlew\.bat\b/, section],
  ['the app launcher', /\bstt\.cmd\b/, section],
  ['the Vulkan driver requirement', /\bVulkan\b/, section],
  ['the CPU fallback', /\bCPU\b/, section],
  ['the per-user data directory', /%APPDATA%\\stt-app/, section],
  ['the ARM64 scope', /\bARM64\b/, section],
];

const missing = facts.filter(([, pattern, text]) => !pattern.test(text)).map(([what]) => what);
if (missing.length > 0) {
  console.error('README.md does not document the Windows path:');
  for (const what of missing) {
    console.error(`  missing ${what}`);
  }
  process.exit(1);
}
console.log('README.md documents the Windows path');
