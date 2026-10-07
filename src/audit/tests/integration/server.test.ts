import assert from 'node:assert/strict';
import { request } from 'node:http';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { after, before, describe, test } from 'node:test';
import {
  ALLOWLIST, dumpDir, RAW_FROM, RAW_TO, rows, runServerExpectingExit, sensitiveExtras, sensitiveValues, startServer,
  tempDir, TOKEN, uuid, validInput, type RunningServer,
} from '../helpers.js';

const auth = { Authorization: `Bearer ${TOKEN}` };
const json = { 'Content-Type': 'application/json' };
const post = (srv: RunningServer, body: unknown, headers: Record<string, string> = { ...auth, ...json }) =>
  fetch(`${srv.url}/events`, { method: 'POST', headers, body: typeof body === 'string' ? body : JSON.stringify(body) });
const list = async (srv: RunningServer) =>
  ((await (await fetch(`${srv.url}/events`, { headers: auth })).json()) as { events: Array<Record<string, unknown>> }).events;
const countFor = (dbPath: string, id: string) => rows(dbPath, 'SELECT * FROM events WHERE event_id = ?', id).length;

/** Exact-size raw POST (fetch may not be able to observe a 413 sent mid-upload). */
function rawPost(srv: RunningServer, body: string): Promise<number> {
  return new Promise((res, rej) => {
    const req = request(`${srv.url}/events`, { method: 'POST', headers: { ...auth, ...json, 'Content-Length': Buffer.byteLength(body) } },
      r => { r.resume(); res(r.statusCode ?? 0); });
    req.on('error', rej);
    req.end(body);
  });
}
/** A valid event JSON padded with an ignored field to exactly `bytes` bytes. */
function paddedEvent(id: string, bytes: number): string {
  const base = JSON.stringify({ ...validInput({ eventId: id }), pad: '' });
  return base.replace('"pad":""', `"pad":"${'x'.repeat(bytes - Buffer.byteLength(base))}"`);
}

describe('audit HTTP API against a real temporary SQLite database', () => {
  let tmp: ReturnType<typeof tempDir>;
  let dbPath: string;
  let srv: RunningServer;
  before(async () => { tmp = tempDir(); dbPath = join(tmp.dir, 'audit.sqlite'); srv = await startServer(dbPath); });
  after(async () => { await srv.stop(); tmp.cleanup(); });

  test('GET /health is public and reports ready', async () => {
    const res = await fetch(`${srv.url}/health`);
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { status: 'ok' });
  });

  for (const [name, headers] of [
    ['missing token', {}],
    ['wrong token', { Authorization: 'Bearer wrong-token-value' }],
    ['wrong token of identical length', { Authorization: `Bearer ${TOKEN.replace(/.$/, 'X')}` }],
    ['token without Bearer prefix', { Authorization: TOKEN }],
    ['wrong scheme', { Authorization: `Basic ${TOKEN}` }],
    ['lower-case scheme with trailing space', { Authorization: `bearer ${TOKEN} ` }],
  ] as const) {
    test(`${name}: 401 on read and write, nothing stored, no event data returned`, async () => {
      const read = await fetch(`${srv.url}/events`, { headers });
      assert.equal(read.status, 401);
      assert.deepEqual(await read.json(), { error: 'Unauthorized' });
      const write = await post(srv, validInput({ eventId: uuid(900) }), { ...headers, ...json });
      assert.equal(write.status, 401);
      assert.equal(countFor(dbPath, uuid(900)), 0);
    });
  }

  test('authentication is checked before routing: unauthenticated unknown route is 401, not 404', async () => {
    assert.equal((await fetch(`${srv.url}/admin`)).status, 401);
  });

  test('valid payment returns 201 with masked accounts, allowlisted fields and a server UTC timestamp', async () => {
    const started = Date.now();
    const res = await post(srv, validInput({ eventId: uuid(1), recordedAt: '1999-01-01T00:00:00Z', ...sensitiveExtras }));
    assert.equal(res.status, 201);
    assert.equal(res.headers.get('cache-control'), 'no-store');
    assert.match(res.headers.get('content-type') ?? '', /^application\/json/);
    const body = await res.json() as { created: boolean; event: Record<string, unknown> };
    assert.equal(body.created, true);
    assert.deepEqual(Object.keys(body.event).sort(), [...ALLOWLIST, 'recordedAt'].sort());
    assert.equal(body.event.fromAccount, '******6111');
    assert.equal(body.event.toAccount, '******3433');
    assert.match(String(body.event.recordedAt), /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/);
    const recorded = Date.parse(String(body.event.recordedAt));
    assert.ok(recorded >= started - 1000 && recorded <= Date.now() + 1000, 'client-supplied recordedAt must be ignored');
    const text = JSON.stringify(body);
    for (const secret of [RAW_FROM, RAW_TO, ...sensitiveValues]) assert.ok(!text.includes(secret), `response leaked ${secret}`);
  });

  test('valid deposit returns 201 and is persisted', async () => {
    const res = await post(srv, validInput({ eventId: uuid(7), action: 'deposit', amountCents: 2500 }));
    assert.equal(res.status, 201);
    const [row] = rows(dbPath, 'SELECT payload FROM events WHERE event_id = ?', uuid(7));
    assert.deepEqual(JSON.parse(String(row.payload)), { ...validInput({ eventId: uuid(7), action: 'deposit', amountCents: 2500 }), fromAccount: '******6111', toAccount: '******3433' });
  });

  test('identical replay (incl. upper-case ID) returns 200 with original record; conflicting replay returns 409; one row', async () => {
    const first = await post(srv, validInput({ eventId: uuid(2) }));
    assert.equal(first.status, 201);
    const original = (await first.json() as { event: { recordedAt: string } }).event;
    const replay = await post(srv, validInput({ eventId: uuid(2).toUpperCase() }));
    assert.equal(replay.status, 200);
    assert.deepEqual(await replay.json(), { created: false, event: original });
    const conflict = await post(srv, validInput({ eventId: uuid(2), amountCents: 1 }));
    assert.equal(conflict.status, 409);
    assert.deepEqual(await conflict.json(), { error: 'Event ID conflicts with an existing record' });
    const stored = rows(dbPath, 'SELECT payload FROM events WHERE event_id = ?', uuid(2));
    assert.equal(stored.length, 1);
    assert.equal(JSON.parse(String(stored[0].payload)).amountCents, 10000);
  });

  test('concurrent duplicate deliveries of one event create exactly one row', async () => {
    const statuses = (await Promise.all(Array.from({ length: 10 }, () => post(srv, validInput({ eventId: uuid(8) }))))).map(r => r.status);
    assert.deepEqual(statuses.filter(s => s === 201).length, 1);
    assert.deepEqual(statuses.filter(s => s === 200).length, 9);
    assert.equal(countFor(dbPath, uuid(8)), 1);
  });

  test('database files never contain raw account numbers or sensitive request fields', async () => {
    assert.equal((await post(srv, validInput({ eventId: uuid(3), ...sensitiveExtras }))).status, 201);
    const files = dumpDir(tmp.dir);
    for (const secret of [RAW_FROM, RAW_TO, ...sensitiveValues, '1999-01-01']) assert.ok(!files.includes(secret), `database leaked ${secret}`);
  });

  for (const [name, body, message] of [
    ['zero amount', validInput({ eventId: uuid(4), amountCents: 0 }), 'amountCents must be a positive safe integer'],
    ['string amount', validInput({ eventId: uuid(4), amountCents: '100' }), 'amountCents must be a positive safe integer'],
    ['failed outcome', validInput({ eventId: uuid(4), outcome: 'failed' }), 'Invalid outcome'],
    ['unsupported action', validInput({ eventId: uuid(4), action: 'withdrawal' }), 'Invalid action'],
    ['null account', validInput({ eventId: uuid(4), toAccount: null }), 'Accounts must contain ten digits'],
    ['11-digit account', validInput({ eventId: uuid(4), toAccount: '10336234339' }), 'Accounts must contain ten digits'],
    ['missing eventId', { ...validInput(), eventId: undefined }, 'eventId must be a UUID'],
    ['array body', [validInput({ eventId: uuid(4) })], 'Expected an event object'],
    ['JSON null body', null, 'Expected an event object'],
  ] as const) {
    test(`invalid event (${name}) returns 400 with a generic message and is not stored`, async () => {
      const res = await post(srv, body);
      assert.equal(res.status, 400);
      const text = await res.text();
      assert.deepEqual(JSON.parse(text), { error: message });
      assert.ok(!text.includes(RAW_TO) && !text.includes(RAW_FROM), 'error echoed an account number');
      assert.equal(countFor(dbPath, uuid(4)), 0);
    });
  }

  test('malformed JSON returns 400 and stores nothing', async () => {
    const before = rows(dbPath).length;
    const res = await post(srv, '{"eventId":');
    assert.equal(res.status, 400);
    assert.deepEqual(await res.json(), { error: 'Invalid JSON' });
    assert.equal(rows(dbPath).length, before);
  });

  test('missing or non-JSON media type returns 415; JSON with charset parameter is accepted', async () => {
    assert.equal((await post(srv, JSON.stringify(validInput({ eventId: uuid(5) })), { ...auth, 'Content-Type': 'text/plain' })).status, 415);
    assert.equal((await post(srv, JSON.stringify(validInput({ eventId: uuid(5) })), { ...auth, 'Content-Type': 'application/x-www-form-urlencoded' })).status, 415);
    const noType = await fetch(`${srv.url}/events`, { method: 'POST', headers: auth, body: new Uint8Array(Buffer.from(JSON.stringify(validInput({ eventId: uuid(5) })))) });
    assert.equal(noType.status, 415);
    assert.equal(countFor(dbPath, uuid(5)), 0);
    assert.equal((await post(srv, JSON.stringify(validInput({ eventId: uuid(5) })), { ...auth, 'Content-Type': 'application/json; charset=utf-8' })).status, 201);
  });

  test('media type matching is case-insensitive (RFC 9110 section 8.3.1): Application/JSON is accepted', async () => {
    const res = await post(srv, JSON.stringify(validInput({ eventId: uuid(12) })), { ...auth, 'Content-Type': 'Application/JSON' });
    assert.equal(res.status, 201);
  });

  test('payload of exactly 16 KiB is accepted; 16 KiB + 1 byte returns 413 and is not stored', async () => {
    assert.equal(await rawPost(srv, paddedEvent(uuid(13), 16384)), 201);
    assert.equal(await rawPost(srv, paddedEvent(uuid(14), 16385)), 413);
    assert.equal(countFor(dbPath, uuid(14)), 0);
    const big = await post(srv, JSON.stringify({ ...validInput({ eventId: uuid(6) }), pad: 'x'.repeat(64 * 1024) }));
    assert.equal(big.status, 413);
    assert.deepEqual(await big.json(), { error: 'Event too large' });
    assert.equal(countFor(dbPath, uuid(6)), 0);
  });

  test('unknown route returns 404 and unsupported methods on /events return 405', async () => {
    assert.equal((await fetch(`${srv.url}/nope`, { headers: auth })).status, 404);
    assert.equal((await fetch(`${srv.url}/events/1`, { headers: auth })).status, 404);
    for (const method of ['DELETE', 'PUT', 'PATCH']) {
      const res = await fetch(`${srv.url}/events`, { method, headers: { ...auth, ...json }, body: method === 'DELETE' ? undefined : '{}' });
      assert.equal(res.status, 405, method);
      assert.deepEqual(await res.json(), { error: 'Method not allowed' });
    }
  });

  test('successful operations log no account numbers, sensitive fields or the bearer token', () => {
    const logs = srv.logs();
    for (const secret of [RAW_FROM, RAW_TO, TOKEN, ...sensitiveValues]) assert.ok(!logs.includes(secret), `logs leaked ${secret}`);
  });
});

describe('GET /events listing', () => {
  test('returns authenticated records newest first, masked, capped at 100', async () => {
    const tmp = tempDir();
    const srv = await startServer(join(tmp.dir, 'audit.sqlite'));
    try {
      for (let i = 1; i <= 102; i++) assert.equal((await post(srv, validInput({ eventId: uuid(i), amountCents: i }))).status, 201);
      const events = await list(srv);
      assert.equal(events.length, 100);
      assert.deepEqual(events.slice(0, 3).map(e => e.eventId), [uuid(102), uuid(101), uuid(100)]);
      assert.equal(events[99].eventId, uuid(3));
      assert.ok(events.every(e => e.fromAccount === '******6111' && e.toAccount === '******3433'));
      assert.ok(events.every(e => Object.keys(e).sort().join() === [...ALLOWLIST, 'recordedAt'].sort().join()));
    } finally { await srv.stop(); tmp.cleanup(); }
  });
});

describe('audit service lifecycle and failure handling', () => {
  test('records survive a graceful restart on the same database file', async () => {
    const tmp = tempDir();
    const dbPath = join(tmp.dir, 'audit.sqlite');
    const servers: RunningServer[] = [];
    try {
      servers.push(await startServer(dbPath));
      const created = await (await post(servers[0], validInput({ eventId: uuid(10) }))).json() as { event: unknown };
      assert.equal(await servers[0].stop(), 0, 'graceful shutdown on SIGTERM');
      servers.push(await startServer(dbPath));
      assert.deepEqual(await list(servers[1]), [created.event]);
      assert.equal((await post(servers[1], validInput({ eventId: uuid(10) }))).status, 200, 'idempotency holds across restart');
    } finally { await Promise.all(servers.map(s => s.stop())); tmp.cleanup(); }
  });

  test('storage failure returns generic 503 on write and read; logs/responses contain no payload, SQL detail or token', async () => {
    const tmp = tempDir();
    const dbPath = join(tmp.dir, 'audit.sqlite');
    const srv = await startServer(dbPath);
    try {
      const saboteur = new DatabaseSync(dbPath);
      saboteur.exec('DROP TABLE events');
      saboteur.close();
      const write = await post(srv, validInput({ eventId: uuid(11), ...sensitiveExtras }));
      assert.equal(write.status, 503);
      const writeBody = await write.text();
      assert.equal(writeBody, JSON.stringify({ error: 'Audit storage unavailable' }));
      const read = await fetch(`${srv.url}/events`, { headers: auth });
      assert.equal(read.status, 503);
      const readBody = await read.text();
      const logs = srv.logs();
      assert.match(logs, /Audit storage operation failed/);
      for (const secret of [RAW_FROM, RAW_TO, TOKEN, uuid(11), 'no such table', 'SQLITE', 'events', ...sensitiveValues]) {
        assert.ok(!logs.includes(secret) && !writeBody.includes(secret) && !readBody.includes(secret), `leaked ${secret}`);
      }
    } finally { await srv.stop(); tmp.cleanup(); }
  });

  test('refuses to start without AUDIT_TOKEN and does not create the database', async () => {
    const tmp = tempDir();
    try {
      const { code, output } = await runServerExpectingExit({ AUDIT_TOKEN: '', AUDIT_DB_PATH: join(tmp.dir, 'a.sqlite'), PORT: '0' });
      assert.notEqual(code, 0);
      assert.match(output, /AUDIT_TOKEN is required/);
      assert.equal(dumpDir(tmp.dir), '');
    } finally { tmp.cleanup(); }
  });
});
