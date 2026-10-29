// Checks that the Python oracle's retirement holds (roadmap item 75): the tree holds no Python
// file, docs/oracle/ is gone, and no live document names the oracle. The tree is walked rather
// than asked of git, so a planted symlinked sandbox runs the same rule; historical ADRs
// (docs/decisions/) and the specs moved out of the roadmap (docs/specs/) are records and are not
// read. The live-document rule lives in `oracle-retirement.ts`, shared with its test. Exits 1 on
// any violation.
//
//   node docs/check-oracle-retired.ts [root]     (default: .)
import { existsSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { liveDocumentViolations, treeFiles } from './oracle-retirement.ts';

const root = process.argv[2] ?? '.';

const violations: string[] = [];
for (const file of treeFiles(root, new Set()).filter((path) => path.endsWith('.py'))) {
  violations.push(`${relative(root, file).split(sep).join('/')} — Python is not allowed`);
}
if (existsSync(join(root, 'docs/oracle'))) {
  violations.push('docs/oracle/ — the oracle is retired');
}
violations.push(...liveDocumentViolations(root));

if (violations.length > 0) {
  console.error('The Python oracle is not retired:');
  for (const violation of violations) {
    console.error(`  ${violation}`);
  }
  process.exit(1);
}
console.log('No Python file is in the tree and docs/oracle/ is gone.');
console.log('No live document names the oracle.');
