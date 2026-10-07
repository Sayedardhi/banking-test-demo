import { spawn, type ChildProcess } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';

/** Compiled from src/server.ts by `npm run build:tests` (same source as the production build). */
export const SERVER_ENTRY = fileURLToPath(new URL('../../src/server.js', import.meta.url));

export interface RunningServer {
  baseUrl: string;
  child: ChildProcess;
  output: () => string;
  stop: () => Promise<number | null>;
}

async function freePort(): Promise<number> {
  const probe = createServer();
  probe.listen(0, '127.0.0.1');
  await once(probe, 'listening');
  const { port } = probe.address() as { port: number };
  await new Promise(resolve => probe.close(resolve));
  return port;
}

/** Spawns the real audit server against a database path. Resolves once /health answers 200. */
export async function startServer(env: Record<string, string | undefined>): Promise<RunningServer> {
  const port = await freePort();
  const child = spawn(process.execPath, [SERVER_ENTRY], {
    env: { ...process.env, PORT: String(port), ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let log = '';
  child.stdout!.on('data', d => { log += d; });
  child.stderr!.on('data', d => { log += d; });
  const baseUrl = `http://127.0.0.1:${port}`;
  const deadline = Date.now() + 10_000;
  for (;;) {
    if (child.exitCode !== null) throw new Error(`audit server exited ${child.exitCode}: ${log}`);
    try {
      const res = await fetch(`${baseUrl}/health`);
      if (res.status === 200) break;
    } catch { /* not listening yet */ }
    if (Date.now() > deadline) { child.kill('SIGKILL'); throw new Error(`audit server not ready: ${log}`); }
    await new Promise(r => setTimeout(r, 50));
  }
  return {
    baseUrl, child, output: () => log,
    // SIGTERM exercises the graceful shutdown path and lets V8 flush coverage for this process.
    stop: async () => {
      if (child.exitCode !== null) return child.exitCode;
      const exited = once(child, 'exit');
      child.kill('SIGTERM');
      const [code] = await exited;
      return code as number | null;
    },
  };
}

/** Runs the server expecting it to refuse to start; returns its exit code and output. */
export async function expectStartupFailure(env: Record<string, string | undefined>): Promise<{ code: number | null; output: string }> {
  const child = spawn(process.execPath, [SERVER_ENTRY], {
    env: { ...process.env, PORT: String(await freePort()), ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  child.stdout!.on('data', d => { output += d; });
  child.stderr!.on('data', d => { output += d; });
  const timer = setTimeout(() => child.kill('SIGKILL'), 10_000);
  const [code] = await once(child, 'exit');
  clearTimeout(timer);
  return { code: code as number | null, output };
}
