import assert from 'node:assert/strict';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { afterEach, beforeEach, describe, test } from 'node:test';
import { FROM, TO, tempDir, validEvent } from '../helpers/fixtures.js';
import { expectStartupFailure, startServer, type RunningServer } from '../helpers/server-process.js';

const TOKEN = 'lifecycle-token-91c2';
const auth = { Authorization: `Bearer ${TOKEN}` };
const json = { ...auth, 'Content-Type': 'application/json' };

let tmp: ReturnType<typeof tempDir>;
let dbPath: string;
const running: RunningServer[] = [];
const start = async (env: Record<string, string> = {}) => {
  const s = await startServer({ AUDIT_TOKEN: TOKEN, AUDIT_DB_PATH: dbPath, ...env });
  running.push(s);
  return s;
};
const post = (s: RunningServer, body: unknown) =>
  fetch(`${s.baseUrl}/events`, { method: 'POST', headers: json, body: JSON.stringify(body) });
const countRows = () => {
  const db = new DatabaseSync(dbPath, { readOnly: true });
  try { return (db.prepare('SELECT COUNT(*) AS n FROM events').get() as { n: number }).n; } finally { db.close(); }
};

beforeEach(() => { tmp = tempDir(); dbPath = join(tmp.dir, 'audit.sqlite'); });
afterEach(async () => {
  for (const s of running.splice(0)) await s.stop();
  tmp.cleanup();
});

describe('durability across restarts', () => {
  test('records survive a graceful restart; retries afterwards dedupe against the persisted row', async () => {
    const event = validEvent({ action: 'deposit', amountCents: 125 });
    const first = await start();
    const created = await (await post(first, event)).json();
    assert.equal(await first.stop(), 0, 'SIGTERM should shut down cleanly with exit code 0');

    const second = await start();
    const { events } = await (await fetch(`${second.baseUrl}/events`, { headers: auth })).json();
    assert.deepEqual(events, [created.event]);
    const retry = await post(second, event);
    assert.equal(retry.status, 200);
    assert.deepEqual((await retry.json()).event.recordedAt, created.event.recordedAt);
    assert.equal((await post(second, { ...event, amountCents: 126 })).status, 409);
    assert.equal(countRows(), 1);
  });

  test('a restart with a different token locks out the old token (no stale credentials)', async () => {
    const first = await start();
    await post(first, validEvent());
    await first.stop();
    const second = await start({ AUDIT_TOKEN: 'rotated-token-0001' });
    assert.equal((await fetch(`${second.baseUrl}/events`, { headers: auth })).status, 401);
    const rotated = await fetch(`${second.baseUrl}/events`, { headers: { Authorization: 'Bearer rotated-token-0001' } });
    assert.equal((await rotated.json()).events.length, 1);
  });
});

describe('storage dependency failures', () => {
  test('a locked database returns generic 503, writes nothing, logs no details, and recovers after release', async () => {
    const server = await start();
    const event = validEvent();
    const blocker = new DatabaseSync(dbPath);
    blocker.exec('BEGIN EXCLUSIVE');
    try {
      const res = await post(server, event);
      assert.equal(res.status, 503);
      const body = await res.text();
      assert.deepEqual(JSON.parse(body), { error: 'Audit storage unavailable' });
      for (const detail of ['locked', 'SQLITE', 'INSERT', dbPath, FROM, TO, TOKEN]) {
        assert.ok(!body.includes(detail), `response leaked ${detail}`);
      }
    } finally {
      blocker.exec('ROLLBACK');
      blocker.close();
    }
    const log = server.output();
    assert.match(log, /Audit storage operation failed/);
    for (const detail of ['locked', 'SQLITE', 'INSERT', dbPath, FROM, TO, TOKEN, event.eventId]) {
      assert.ok(!log.includes(detail), `log leaked ${detail}`);
    }
    assert.equal(countRows(), 0);
    assert.equal((await post(server, event)).status, 201, 'the same event can be delivered once storage recovers');
    assert.equal(countRows(), 1);
  });

  test('a missing events table (corrupted schema) yields 503 on read and write, not a crash', async () => {
    const server = await start();
    const db = new DatabaseSync(dbPath);
    db.exec('DROP TABLE events');
    db.close();
    assert.equal((await post(server, validEvent())).status, 503);
    assert.equal((await fetch(`${server.baseUrl}/events`, { headers: auth })).status, 503);
    assert.equal((await fetch(`${server.baseUrl}/health`)).status, 200, '/health only checks the connection');
    assert.equal(server.child.exitCode, null, 'server keeps running');
  });
});

describe('fail-closed startup', () => {
  test('refuses to start without AUDIT_TOKEN', async () => {
    const { code, output } = await expectStartupFailure({ AUDIT_TOKEN: undefined, AUDIT_DB_PATH: dbPath });
    assert.notEqual(code, 0);
    assert.match(output, /AUDIT_TOKEN is required/);
    assert.doesNotMatch(output, /Audit service ready/);
  });

  test('refuses to start with an empty AUDIT_TOKEN', async () => {
    const { code, output } = await expectStartupFailure({ AUDIT_TOKEN: '', AUDIT_DB_PATH: dbPath });
    assert.notEqual(code, 0);
    assert.match(output, /AUDIT_TOKEN is required/);
  });

  test('refuses to start when the database path cannot be opened', async () => {
    const { code, output } = await expectStartupFailure({ AUDIT_TOKEN: TOKEN, AUDIT_DB_PATH: join(tmp.dir, 'missing', 'dir', 'audit.sqlite') });
    assert.notEqual(code, 0);
    assert.doesNotMatch(output, /Audit service ready/);
  });
});
