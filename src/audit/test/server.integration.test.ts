import assert from 'node:assert/strict';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { after, before, describe, test } from 'node:test';
import { runServerExpectingExit, startServer, tempDir, TOKEN, validInput, type RunningServer } from './helpers.js';

const auth = { Authorization: `Bearer ${TOKEN}` };
const json = { 'Content-Type': 'application/json' };
const post = (srv: RunningServer, body: unknown, headers: Record<string, string> = { ...auth, ...json }) =>
  fetch(`${srv.url}/events`, { method: 'POST', headers, body: typeof body === 'string' ? body : JSON.stringify(body) });
const uuid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

describe('audit HTTP API against a real temporary SQLite database', () => {
  let tmp: ReturnType<typeof tempDir>;
  let dbPath: string;
  let srv: RunningServer;
  before(async () => { tmp = tempDir(); dbPath = join(tmp.dir, 'audit.sqlite'); srv = await startServer(dbPath); });
  after(async () => { await srv.stop(); tmp.cleanup(); });

  test('health is public and reports ready', async () => {
    const res = await fetch(`${srv.url}/health`);
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { status: 'ok' });
  });

  for (const [name, headers] of [
    ['missing token', {}],
    ['wrong token', { Authorization: 'Bearer wrong-token-value' }],
    ['token without Bearer prefix', { Authorization: TOKEN }],
    ['wrong scheme', { Authorization: `Basic ${TOKEN}` }],
  ] as const) {
    test(`${name} is rejected with 401 on read and write, and nothing is stored`, async () => {
      assert.equal((await fetch(`${srv.url}/events`, { headers })).status, 401);
      const res = await post(srv, validInput({ eventId: uuid(900) }), { ...headers, ...json });
      assert.equal(res.status, 401);
      const list = await (await fetch(`${srv.url}/events`, { headers: auth })).json() as { events: Array<{ eventId: string }> };
      assert.ok(!list.events.some(e => e.eventId === uuid(900)));
    });
  }

  test('valid payment returns 201 with masked accounts and server timestamp', async () => {
    const res = await post(srv, validInput({ eventId: uuid(1), recordedAt: '1999-01-01T00:00:00Z', password: 'hunter2' }));
    assert.equal(res.status, 201);
    const body = await res.json() as { created: boolean; event: Record<string, unknown> };
    assert.equal(body.created, true);
    assert.equal(body.event.fromAccount, '******6111');
    assert.equal(body.event.toAccount, '******3433');
    assert.equal(body.event.password, undefined);
    assert.match(String(body.event.recordedAt), /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/);
    assert.ok(Date.now() - Date.parse(String(body.event.recordedAt)) < 60_000, 'client-supplied recordedAt must be ignored');
    assert.equal(res.headers.get('cache-control'), 'no-store');
  });

  test('replay returns 200 without a new row; conflicting replay returns 409', async () => {
    const first = await post(srv, validInput({ eventId: uuid(2) }));
    assert.equal(first.status, 201);
    const replay = await post(srv, validInput({ eventId: uuid(2).toUpperCase() }));
    assert.equal(replay.status, 200);
    const conflict = await post(srv, validInput({ eventId: uuid(2), amountCents: 1 }));
    assert.equal(conflict.status, 409);
    const db = new DatabaseSync(dbPath, { readOnly: true });
    const rows = db.prepare('SELECT payload FROM events WHERE event_id = ?').all(uuid(2)) as Array<{ payload: string }>;
    db.close();
    assert.equal(rows.length, 1);
    assert.equal(JSON.parse(rows[0].payload).amountCents, 10000);
  });

  test('database never contains raw account numbers or sensitive request fields', async () => {
    await post(srv, validInput({ eventId: uuid(3), ssn: '111-22-3333', token: 'eyJhbGciOi', metadata: { email: 'a@example.com' } }));
    const db = new DatabaseSync(dbPath, { readOnly: true });
    const dump = JSON.stringify(db.prepare('SELECT * FROM events').all());
    db.close();
    for (const secret of ['1011226111', '1033623433', '111-22-3333', 'eyJhbGciOi', 'a@example.com', 'hunter2', '1999-01-01']) {
      assert.ok(!dump.includes(secret), `database leaked ${secret}`);
    }
  });

  test('invalid events are rejected with 400 and not stored', async () => {
    for (const body of [validInput({ eventId: uuid(4), amountCents: 0 }), validInput({ eventId: uuid(4), outcome: 'failed' }),
      validInput({ eventId: uuid(4), toAccount: null }), [validInput({ eventId: uuid(4) })]]) {
      assert.equal((await post(srv, body)).status, 400);
    }
    const db = new DatabaseSync(dbPath, { readOnly: true });
    assert.equal(db.prepare('SELECT COUNT(*) AS n FROM events WHERE event_id = ?').get(uuid(4))!.n, 0);
    db.close();
  });

  test('protocol errors: malformed JSON 400, wrong media type 415, oversize 413, unknown route 404, bad method 405', async () => {
    assert.equal((await post(srv, '{"eventId":')).status, 400);
    assert.equal((await post(srv, JSON.stringify(validInput()), { ...auth, 'Content-Type': 'text/plain' })).status, 415);
    assert.equal((await post(srv, JSON.stringify(validInput({ eventId: uuid(5) })), { ...auth, 'Content-Type': 'application/json; charset=utf-8' })).status, 201);
    assert.equal((await post(srv, JSON.stringify({ ...validInput({ eventId: uuid(6) }), pad: 'x'.repeat(17_000) }))).status, 413);
    assert.equal((await fetch(`${srv.url}/nope`, { headers: auth })).status, 404);
    assert.equal((await fetch(`${srv.url}/events`, { method: 'DELETE', headers: auth })).status, 405);
  });

  test('GET /events lists newest first', async () => {
    const { events } = await (await fetch(`${srv.url}/events`, { headers: auth })).json() as { events: Array<{ eventId: string }> };
    assert.equal(events[0].eventId, uuid(5));
    assert.ok(events.findIndex(e => e.eventId === uuid(1)) > events.findIndex(e => e.eventId === uuid(2)));
  });
});

describe('audit service lifecycle and failure handling', () => {
  test('records survive a service restart on the same database file', async () => {
    const tmp = tempDir();
    const dbPath = join(tmp.dir, 'audit.sqlite');
    try {
      let srv = await startServer(dbPath);
      assert.equal((await post(srv, validInput({ eventId: uuid(10) }))).status, 201);
      assert.equal(await srv.stop(), 0, 'graceful shutdown on SIGTERM');
      srv = await startServer(dbPath);
      const { events } = await (await fetch(`${srv.url}/events`, { headers: auth })).json() as { events: Array<{ eventId: string }> };
      assert.deepEqual(events.map(e => e.eventId), [uuid(10)]);
      await srv.stop();
    } finally { tmp.cleanup(); }
  });

  test('storage failure returns generic 503 and logs no payload, accounts, or token', async () => {
    const tmp = tempDir();
    const dbPath = join(tmp.dir, 'audit.sqlite');
    const srv = await startServer(dbPath);
    try {
      const saboteur = new DatabaseSync(dbPath);
      saboteur.exec('DROP TABLE events');
      saboteur.close();
      const res = await post(srv, validInput({ eventId: uuid(11), ssn: '111-22-3333' }));
      assert.equal(res.status, 503);
      const body = await res.text();
      assert.equal(body, JSON.stringify({ error: 'Audit storage unavailable' }));
      const logs = srv.logs();
      assert.match(logs, /Audit storage operation failed/);
      for (const secret of ['1011226111', '1033623433', '111-22-3333', TOKEN, 'no such table', uuid(11)]) {
        assert.ok(!logs.includes(secret) && !body.includes(secret), `leaked ${secret}`);
      }
    } finally { await srv.stop(); tmp.cleanup(); }
  });

  test('refuses to start without AUDIT_TOKEN', async () => {
    const tmp = tempDir();
    try {
      const { code, output } = await runServerExpectingExit({ AUDIT_TOKEN: '', AUDIT_DB_PATH: join(tmp.dir, 'a.sqlite'), PORT: '0' });
      assert.notEqual(code, 0);
      assert.match(output, /AUDIT_TOKEN is required/);
    } finally { tmp.cleanup(); }
  });
});
