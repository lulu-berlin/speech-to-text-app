// Checks that README.md documents the Windows path (roadmap item 49, test-list item 9): the
// ways-to-run table says Windows x64 is supported, a Windows section exists, and it names the
// bootstrap commands, the Vulkan driver requirement, the CPU fallback, the per-user data
// directory and the x64-only scope. The prose itself is read by the user; this pin keeps those
// facts from being dropped silently. Exits 1 on any missing fact.
//
//   node docs/check-readme-windows.mjs
import { readFileSync } from 'node:fs';

const readme = readFileSync(new URL('../README.md', import.meta.url), 'utf8');

const facts = [
  ['a Windows x64 entry in the ways-to-run table', /\[Gradle without Nix\]\(#gradle-without-nix\)[^\n]*Windows x64/],
  ['a Windows section', /^#{3,4} Windows x64$/m],
  ['the Gradle bootstrap command', /\bgradlew\.bat\b/],
  ['the app launcher', /\bstt\.cmd\b/],
  ['the Vulkan driver requirement', /\bVulkan\b/],
  ['the CPU fallback', /\bCPU\b/],
  ['the per-user data directory', /%APPDATA%\\stt-app/],
  ['the ARM64 scope', /\bARM64\b/],
];

const missing = facts.filter(([, pattern]) => !pattern.test(readme)).map(([what]) => what);
if (missing.length > 0) {
  console.error('README.md does not document the Windows path:');
  for (const what of missing) {
    console.error(`  missing ${what}`);
  }
  process.exit(1);
}
console.log('README.md documents the Windows path');
