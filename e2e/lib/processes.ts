// Portable process listing for the e2e harness (roadmap item 48). Linux has /proc; a stock
// Ubuntu container has no `ps` binary, so the /proc path stays the default there. macOS has no
// /proc, so the same rows come from `ps -axo pid=,ppid=,command=` instead. Windows has neither,
// so the rows come from `Get-CimInstance Win32_Process` (roadmap item 79).
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

/** One process row: the pid, its parent pid and the full command line. */
export interface ProcessRow {
  pid: number;
  ppid: number;
  command: string;
}

/** How this host's process rows are obtained; [platform]/[procRoot]/… only matter to the selector. */
export interface ListingOptions {
  procRoot?: string;
  psOutput?: (() => string) | null;
  platform?: NodeJS.Platform;
  cimOutput?: (() => string) | null;
}

/**
 * Every process row on this host, from the listing [processListing] picks.
 */
export function processes(options: ListingOptions = {}): ProcessRow[] {
  return processListing(options)();
}

/**
 * The listing for this host, in order of precedence: an injected output (CIM, then `ps`), a /proc
 * that exists, Windows' CIM query, else `ps`. Injected input and a real /proc come before the
 * platform default, so a fixture or a fake /proc root means the same thing on every host.
 */
function processListing({
  procRoot = '/proc',
  psOutput = null,
  platform = process.platform,
  cimOutput = null,
}: ListingOptions): () => ProcessRow[] {
  if (cimOutput !== null) return () => parseCim(cimOutput());
  if (psOutput !== null) return () => parsePs(psOutput());
  if (existsSync(join(procRoot, 'self', 'stat'))) return () => procProcesses(procRoot);
  if (platform === 'win32') return () => parseCim(runCim());
  return () => parsePs(runPs());
}

function runPs(): string {
  return execFileSync('ps', ['-axo', 'pid=,ppid=,command='], { encoding: 'utf8' });
}

// `-Compress` keeps the whole listing on one line; `Windows PowerShell` is on every Windows
// runner (and on Windows 10/11), unlike the out-of-band `pwsh`.
function runCim(): string {
  const script =
    'Get-CimInstance Win32_Process | ' +
    'Select-Object ProcessId,ParentProcessId,CommandLine | ConvertTo-Json -Compress';
  return execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], {
    encoding: 'utf8',
    windowsHide: true,
  });
}

/**
 * Parses the CIM listing: one object per process, or a bare object when only one process matched.
 * A process the query cannot read has a null CommandLine, and some system processes a null parent.
 */
export function parseCim(output: string): ProcessRow[] {
  const text = output.trim();
  if (text === '') return [];
  const parsed: unknown = JSON.parse(text);
  const rows: ProcessRow[] = [];
  for (const entry of Array.isArray(parsed) ? parsed : [parsed]) {
    if (typeof entry !== 'object' || entry === null) continue;
    const {
      ProcessId: pid,
      ParentProcessId: ppid,
      CommandLine: command,
    } = entry as Record<string, unknown>;
    if (typeof pid !== 'number') continue;
    rows.push({
      pid,
      ppid: typeof ppid === 'number' ? ppid : 0,
      command: typeof command === 'string' ? command : '',
    });
  }
  return rows;
}

/** Parses `ps -axo pid=,ppid=,command=` output: padded numbers, then the command (which may contain spaces). */
export function parsePs(output: string): ProcessRow[] {
  const rows: ProcessRow[] = [];
  for (const line of output.split('\n')) {
    const match = /^\s*(\d+)\s+(\d+)\s+(.*)$/.exec(line);
    if (match === null) continue;
    rows.push({ pid: Number(match[1]), ppid: Number(match[2]), command: match[3] ?? '' });
  }
  return rows;
}

function procProcesses(procRoot: string): ProcessRow[] {
  const rows: ProcessRow[] = [];
  for (const entry of readdirSync(procRoot)) {
    if (!/^\d+$/.test(entry)) continue;
    try {
      const command = readFileSync(join(procRoot, entry, 'cmdline'), 'utf8')
        .replaceAll('\0', ' ')
        .trim();
      const stat = readFileSync(join(procRoot, entry, 'stat'), 'utf8');
      const rightmostParen = stat.lastIndexOf(')');
      const ppid = Number(stat.slice(rightmostParen + 2).split(/\s+/)[1] ?? '');
      rows.push({ pid: Number(entry), ppid, command });
    } catch {
      // the process ended between the listing and the read
    }
  }
  return rows;
}

/** The row whose command contains every needle: the app JVM's class name and data directory. */
export function findProcess(rows: ProcessRow[], needles: string[]): ProcessRow | null {
  return rows.find((row) => needles.every((needle) => row.command.includes(needle))) ?? null;
}

/** [pid, parent, …] from [pid] up to init, bounded like the old /proc walk. */
export function ancestors(rows: ProcessRow[], pid: number): number[] {
  const byPid = new Map(rows.map((row) => [row.pid, row]));
  const chain: number[] = [];
  let current = pid;
  for (let guard = 0; current > 1 && guard < 16; guard++) {
    chain.push(current);
    const row = byPid.get(current);
    if (row === undefined) break;
    current = row.ppid;
  }
  return chain;
}
