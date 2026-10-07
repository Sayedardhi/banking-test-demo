import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

export const TOKEN = 'test-audit-token';
export const SERVER = resolve(import.meta.dirname, '../src/server.js');

export const validInput = (overrides: Record<string, unknown> = {}) => ({
  eventId: 'd7633a92-c4c7-4bdf-bd41-46ed17e1a113',
  action: 'payment',
  outcome: 'succeeded',
  amountCents: 10000,
  fromAccount: '1011226111',
  toAccount: '1033623433',
  ...overrides,
});

export function tempDir(): { dir: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), 'audit-test-'));
  return { dir, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
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

export async function startServer(dbPath: string, env: Record<string, string> = {}): Promise<RunningServer> {
  const port = await freePort();
  const child = spawn(process.execPath, [SERVER], {
    env: { ...process.env, AUDIT_TOKEN: TOKEN, AUDIT_DB_PATH: dbPath, PORT: String(port), ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  await new Promise<void>((res, rej) => {
    child.stdout.on('data', d => { output += d; if (output.includes('Audit service ready')) res(); });
    child.stderr.on('data', d => { output += d; });
    child.once('exit', code => rej(new Error(`audit server exited with ${code}: ${output}`)));
  });
  return {
    url: `http://127.0.0.1:${port}`,
    logs: () => output,
    stop: () => new Promise(res => { child.once('exit', code => res(code)); child.kill('SIGTERM'); }),
  };
}

export function runServerExpectingExit(env: Record<string, string>): Promise<{ code: number | null; output: string }> {
  const child = spawn(process.execPath, [SERVER], { env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = '';
  child.stdout.on('data', d => { output += d; });
  child.stderr.on('data', d => { output += d; });
  return new Promise(res => child.once('exit', code => res({ code, output })));
}
