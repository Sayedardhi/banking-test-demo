import { spawn } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { DatabaseSync } from 'node:sqlite';

// Synthetic data only.
export const TOKEN = 'test-audit-token-0123456789';
export const RAW_FROM = '1011226111';
export const RAW_TO = '1033623433';
export const SERVER = resolve(import.meta.dirname, '../src/server.js');
export const ALLOWLIST = ['action', 'amountCents', 'eventId', 'fromAccount', 'outcome', 'toAccount'];
export const uuid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

export const validInput = (overrides: Record<string, unknown> = {}) => ({
  eventId: 'd7633a92-c4c7-4bdf-bd41-46ed17e1a113',
  action: 'payment',
  outcome: 'succeeded',
  amountCents: 10000,
  fromAccount: RAW_FROM,
  toAccount: RAW_TO,
  ...overrides,
});

export const sensitiveExtras = {
  password: 'hunter2-synthetic',
  token: 'eyJhbGciOiJSUzI1NiJ9.synthetic',
  ssn: '111-22-3333',
  email: 'synthetic.user@example.com',
  metadata: { nested: { ssn: '444-55-6666', note: 'nested-secret-value' } },
};
export const sensitiveValues = ['hunter2-synthetic', 'eyJhbGciOiJSUzI1NiJ9', '111-22-3333',
  'synthetic.user@example.com', '444-55-6666', 'nested-secret-value'];

export function tempDir(): { dir: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), 'audit-test-'));
  return { dir, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

/** Concatenated bytes of every file in the directory (database, WAL and SHM sidecars). */
export function dumpDir(dir: string): string {
  return readdirSync(dir).map(f => readFileSync(join(dir, f)).toString('latin1')).join('\n');
}

export function rows(dbPath: string, sql = 'SELECT * FROM events ORDER BY sequence', ...params: string[]) {
  const db = new DatabaseSync(dbPath, { readOnly: true });
  try { return db.prepare(sql).all(...params) as Array<Record<string, unknown>>; } finally { db.close(); }
}

async function freePort(): Promise<number> {
  return new Promise((res, rej) => {
    const srv = createServer().listen(0, '127.0.0.1', () => {
      const { port } = srv.address() as { port: number };
      srv.close(() => res(port));
    }).on('error', rej);
  });
}

export interface RunningServer {
  url: string;
  logs: () => string;
  stop: () => Promise<number | null>;
}

/** Spawn the compiled production server against a temporary database file. */
export async function startServer(dbPath: string, env: Record<string, string> = {}): Promise<RunningServer> {
  const port = await freePort();
  const child = spawn(process.execPath, [SERVER], {
    env: { ...process.env, AUDIT_TOKEN: TOKEN, AUDIT_DB_PATH: dbPath, PORT: String(port), ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  let exited: number | null | undefined;
  const exit = new Promise<number | null>(res => child.once('exit', code => { exited = code; res(code); }));
  await new Promise<void>((res, rej) => {
    const timer = setTimeout(() => rej(new Error(`audit server did not start: ${output}`)), 10_000);
    child.stdout.on('data', d => { output += d; if (output.includes('Audit service ready')) { clearTimeout(timer); res(); } });
    child.stderr.on('data', d => { output += d; });
    exit.then(code => { clearTimeout(timer); rej(new Error(`audit server exited with ${code}: ${output}`)); });
  });
  return {
    url: `http://127.0.0.1:${port}`,
    logs: () => output,
    stop: () => {
      if (exited !== undefined) return Promise.resolve(exited);
      child.kill('SIGTERM');
      return exit;
    },
  };
}

export function runServerExpectingExit(env: Record<string, string>): Promise<{ code: number | null; output: string }> {
  const child = spawn(process.execPath, [SERVER], { env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', d => { output += d; });
  child.stderr.on('data', d => { output += d; });
  const timer = setTimeout(() => child.kill('SIGKILL'), 10_000);
  return new Promise(res => child.once('exit', code => { clearTimeout(timer); res({ code, output }); }));
}
