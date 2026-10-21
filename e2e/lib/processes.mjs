// Portable process listing for the e2e harness (roadmap item 48). Linux has /proc; a stock
// Ubuntu container has no `ps` binary, so the /proc path stays the default there. macOS has no
// /proc, so the same rows come from `ps -axo pid=,ppid=,command=` instead.
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * One process row: the pid, its parent pid and the full command line. Uses /proc when the host
 * has it (unless [psOutput] injects the fallback's output); otherwise `ps`.
 *
 * @param {{procRoot?: string, psOutput?: (() => string) | null}} [options]
 * @returns {{pid: number, ppid: number, command: string}[]}
 */
export function processes({ procRoot = '/proc', psOutput = null } = {}) {
  if (psOutput !== null || !existsSync(join(procRoot, 'self', 'stat'))) {
    return parsePs(psOutput === null ? runPs() : psOutput());
  }
  return procProcesses(procRoot);
}

function runPs() {
  return execFileSync('ps', ['-axo', 'pid=,ppid=,command='], { encoding: 'utf8' });
}

/** Parses `ps -axo pid=,ppid=,command=` output: padded numbers, then the command (which may contain spaces). */
export function parsePs(output) {
  const rows = [];
  for (const line of output.split('\n')) {
    const match = /^\s*(\d+)\s+(\d+)\s+(.*)$/.exec(line);
    if (match === null) continue;
    rows.push({ pid: Number(match[1]), ppid: Number(match[2]), command: match[3] });
  }
  return rows;
}

function procProcesses(procRoot) {
  const rows = [];
  for (const entry of readdirSync(procRoot)) {
    if (!/^\d+$/.test(entry)) continue;
    try {
      const command = readFileSync(join(procRoot, entry, 'cmdline'), 'utf8')
        .replaceAll('\0', ' ')
        .trim();
      const stat = readFileSync(join(procRoot, entry, 'stat'), 'utf8');
      const rightmostParen = stat.lastIndexOf(')');
      const ppid = Number(stat.slice(rightmostParen + 2).split(/\s+/)[1]);
      rows.push({ pid: Number(entry), ppid, command });
    } catch {
      // the process ended between the listing and the read
    }
  }
  return rows;
}

/** The row whose command contains every needle: the app JVM's class name and data directory. */
export function findProcess(rows, needles) {
  return rows.find((row) => needles.every((needle) => row.command.includes(needle))) ?? null;
}

/** [pid, parent, …] from [pid] up to init, bounded like the old /proc walk. */
export function ancestors(rows, pid) {
  const byPid = new Map(rows.map((row) => [row.pid, row]));
  const chain = [];
  let current = pid;
  for (let guard = 0; current > 1 && guard < 16; guard++) {
    chain.push(current);
    const row = byPid.get(current);
    if (row === undefined) break;
    current = row.ppid;
  }
  return chain;
}
