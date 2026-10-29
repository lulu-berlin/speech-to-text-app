// Roadmap item 75's live-document rule, shared by the check that runs in `pnpm --dir docs test`
// and the test that pins it, so the two cannot drift: no document a developer reads as
// instructions, an overview, conventions or the plan names the oracle. Historical records
// (docs/decisions/) and the specs moved out of the roadmap (docs/specs/) are not read. The
// roadmap may name the retirement item by its title (`retire`), and the retirement record itself —
// the one whose title says so — is the record of the removal, not a live claim.
import { existsSync, readdirSync, readFileSync, realpathSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

// Development state, dependencies and checkouts of other branches: not the app's tree.
const SKIPPED: Record<string, true> = {
  '.git': true,
  node_modules: true,
  '.devenv': true,
  workspaces: true,
  dist: true,
};

const LIVE_DOCUMENTS = ['AGENTS.md', 'README.md', 'docs/architecture.md', 'docs/known-issues.md'];
const LIVE_DIRECTORIES = ['docs/conventions', 'roadmap'];

// Every file under [dir], following symlinks (the planted sandbox links the tree in) but visiting
// each real directory once, so a link back up cannot loop.
export function treeFiles(dir: string, seen: Set<string>): string[] {
  const real = realpathSync(dir);
  if (seen.has(real)) {
    return [];
  }
  seen.add(real);
  const found: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (SKIPPED[entry.name] !== undefined) {
      continue;
    }
    const path = join(dir, entry.name);
    const isDirectory =
      entry.isDirectory() || (entry.isSymbolicLink() && statSync(path).isDirectory());
    if (isDirectory) {
      found.push(...treeFiles(path, seen));
    } else {
      found.push(path);
    }
  }
  return found;
}

// The live documents under [root], as `/`-separated paths so the rule reads the same on Windows.
function liveDocuments(root: string): string[] {
  const named = LIVE_DOCUMENTS.filter((file) => existsSync(join(root, file)));
  const walked = LIVE_DIRECTORIES.filter((dir) => existsSync(join(root, dir))).flatMap((dir) =>
    treeFiles(join(root, dir), new Set()).map((file) => relative(root, file).split(sep).join('/')),
  );
  return [...named, ...walked].filter((file) => file.endsWith('.md'));
}

/** Every line of a live document that names the oracle; empty when the retirement holds. */
export function liveDocumentViolations(root: string): string[] {
  const violations: string[] = [];
  for (const file of liveDocuments(root)) {
    const lines = readFileSync(join(root, file), 'utf8').split('\n');
    const retirementRecord = file.startsWith('roadmap/') && /retire/i.test(lines[0] ?? '');
    for (const [index, line] of lines.entries()) {
      if (!/\boracle\b/i.test(line)) {
        continue;
      }
      if (retirementRecord || (file.startsWith('roadmap/') && /retire/i.test(line))) {
        continue;
      }
      violations.push(`${file}:${index + 1}: names the oracle`);
    }
  }
  return violations;
}
