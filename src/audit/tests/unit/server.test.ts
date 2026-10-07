import assert from 'node:assert/strict';
import { once } from 'node:events';
import http from 'node:http';
import type { AddressInfo } from 'node:net';
import { syncBuiltinESMExports } from 'node:module';
import { after, afterEach, before, describe, mock, test } from 'node:test';
import { AuditStore } from '../../src/store.js';
import { FROM, SENSITIVE_EXTRAS, SENSITIVE_STRINGS, TO, validEvent } from '../helpers/fixtures.js';

// The server module listens on import and exports nothing, so capture the http.Server it creates.
// The store behind it is a real in-memory SQLite database; storage failures are injected per test.
const TOKEN = 'unit-test-token';
let server: http.Server;
let base: string;

before(async () => {
  Object.assign(process.env, { AUDIT_TOKEN: TOKEN, AUDIT_DB_PATH: ':memory:', PORT: '0' });
  const realCreateServer = http.createServer;
  const capture = mock.method(http, 'createServer', ((...args: Parameters<typeof http.createServer>) => {
    server = realCreateServer(...args);
    return server;
  }) as typeof http.createServer);
  syncBuiltinESMExports();
  const info = mock.method(console, 'info', () => {});
  await import('../../src/server.js');
  capture.mock.restore();
  syncBuiltinESMExports();
  if (!server.listening) await once(server, 'listening');
  info.mock.restore();
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
after(() => { if (server.listening) { server.closeAllConnections(); server.close(); } });
afterEach(() => mock.restoreAll());

const auth = { Authorization: `Bearer ${TOKEN}` };
const json = { ...auth, 'Content-Type': 'application/json' };
const post = (body: unknown, headers: Record<string, string> = json) =>
  fetch(`${base}/events`, { method: 'POST', headers, body: typeof body === 'string' ? body : JSON.stringify(body) });
const listEvents = async () => (await (await fetch(`${base}/events`, { headers: auth })).json()).events as Array<Record<string, unknown>>;

describe('GET /health', () => {
  test('answers 200 without credentials and reveals nothing but status', async () => {
    const res = await fetch(`${base}/health`);
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { status: 'ok' });
  });

  test('reports 503 when the database cannot be queried', async () => {
    mock.method(AuditStore.prototype, 'ready', () => { throw new Error('SQLITE_CANTOPEN: unable to open /data/audit.sqlite'); });
    const errors = mock.method(console, 'error', () => {});
    const res = await fetch(`${base}/health`);
    assert.equal(res.status, 503);
    assert.deepEqual(await res.json(), { error: 'Audit storage unavailable' });
    assert.deepEqual(errors.mock.calls.map(c => c.arguments), [['Audit storage operation failed']]);
  });
});

describe('authentication (reads and writes)', () => {
  const badHeaders: Array<[string, Record<string, string>]> = [
    ['no Authorization header', {}],
    ['wrong token', { Authorization: 'Bearer not-the-token' }],
    ['token without Bearer scheme', { Authorization: TOKEN }],
    ['lower-case scheme', { Authorization: `bearer ${TOKEN}` }],
    ['Basic scheme', { Authorization: `Basic ${Buffer.from(`audit:${TOKEN}`).toString('base64')}` }],
    ['token prefix', { Authorization: `Bearer ${TOKEN.slice(0, -1)}` }],
    ['token with suffix', { Authorization: `Bearer ${TOKEN}x` }],
    ['empty bearer', { Authorization: 'Bearer ' }],
  ];
  for (const [name, headers] of badHeaders) {
    test(`${name}: GET and POST /events return 401 and nothing is recorded`, async () => {
      const event = validEvent();
      const read = await fetch(`${base}/events`, { headers });
      assert.equal(read.status, 401);
      assert.deepEqual(await read.json(), { error: 'Unauthorized' });
      const write = await post(event, { ...headers, 'Content-Type': 'application/json' });
      assert.equal(write.status, 401);
      assert.ok(!(await listEvents()).some(e => e.eventId === event.eventId));
    });
  }

  test('authentication is checked before routing, so unknown paths do not reveal themselves', async () => {
    assert.equal((await fetch(`${base}/admin`)).status, 401);
    assert.equal((await fetch(`${base}/admin`, { headers: auth })).status, 404);
  });
});

describe('routing and HTTP semantics', () => {
  test('unknown routes return 404, including trailing slashes and query strings', async () => {
    for (const path of ['/', '/event', '/events/', '/events/123', '/health/x']) {
      const res = await fetch(`${base}${path}`, { headers: auth });
      assert.equal(res.status, 404, path);
      assert.deepEqual(await res.json(), { error: 'Not found' });
    }
  });

  test('records cannot be modified or deleted: PUT, PATCH and DELETE /events return 405', async () => {
    const event = validEvent();
    assert.equal((await post(event)).status, 201);
    for (const method of ['PUT', 'PATCH', 'DELETE']) {
      const res = await fetch(`${base}/events`, { method, headers: json, body: JSON.stringify({ ...event, amountCents: 1 }) });
      assert.equal(res.status, 405, method);
      assert.deepEqual(await res.json(), { error: 'Method not allowed' });
    }
    assert.equal((await listEvents()).find(e => e.eventId === event.eventId)?.amountCents, 10_000);
  });

  test('responses are JSON and marked no-store so audit data is not cached', async () => {
    const res = await fetch(`${base}/events`, { headers: auth });
    assert.equal(res.headers.get('content-type'), 'application/json');
    assert.equal(res.headers.get('cache-control'), 'no-store');
  });
});

describe('POST /events', () => {
  test('a valid event returns 201 with the masked record and the server timestamp', async () => {
    const event = validEvent({ action: 'deposit', amountCents: 2550 });
    const before = Date.now();
    const res = await post(event);
    assert.equal(res.status, 201);
    const body = await res.json();
    assert.equal(body.created, true);
    assert.deepEqual({ ...body.event, recordedAt: undefined }, {
      eventId: event.eventId, action: 'deposit', outcome: 'succeeded', amountCents: 2550,
      fromAccount: '******6111', toAccount: '******3433', recordedAt: undefined,
    });
    const recordedAt = Date.parse(body.event.recordedAt);
    assert.match(body.event.recordedAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    assert.ok(recordedAt >= before - 1 && recordedAt <= Date.now() + 1);
  });

  test('extra sensitive fields and a client recordedAt are neither echoed nor stored', async () => {
    const event = { ...validEvent(), ...SENSITIVE_EXTRAS };
    const res = await post(event);
    assert.equal(res.status, 201);
    const echoed = await res.text();
    const stored = JSON.stringify((await listEvents()).find(e => e.eventId === event.eventId));
    for (const secret of SENSITIVE_STRINGS) {
      assert.ok(!echoed.includes(secret), `response leaked ${secret}`);
      assert.ok(!stored.includes(secret), `stored record leaked ${secret}`);
    }
  });

  test('an identical retry returns 200 with the original record; a conflicting reuse returns 409', async () => {
    const event = validEvent();
    const first = await (await post(event)).json();
    const retry = await post({ ...event, eventId: event.eventId.toUpperCase() });
    assert.equal(retry.status, 200);
    assert.deepEqual(await retry.json(), { created: false, event: first.event });
    const conflict = await post({ ...event, amountCents: event.amountCents + 1 });
    assert.equal(conflict.status, 409);
    assert.deepEqual(await conflict.json(), { error: 'Event ID conflicts with an existing record' });
    assert.equal((await listEvents()).filter(e => e.eventId === event.eventId).length, 1);
  });

  test('invalid events return 400 with the validation reason', async () => {
    const res = await post(validEvent({ amountCents: 0 }));
    assert.equal(res.status, 400);
    assert.deepEqual(await res.json(), { error: 'amountCents must be a positive safe integer' });
  });

  test('malformed or empty JSON returns 400', async () => {
    for (const body of ['{"eventId":', '', 'not json', '{"a":1}}']) {
      const res = await post(body);
      assert.equal(res.status, 400, JSON.stringify(body));
      assert.deepEqual(await res.json(), { error: 'Invalid JSON' });
    }
  });

  test('non-JSON media types return 415 before the body is parsed', async () => {
    for (const type of ['text/plain', 'application/x-www-form-urlencoded', 'application/jsonp', 'application/json-patch+json']) {
      const res = await post(validEvent(), { ...auth, 'Content-Type': type });
      assert.equal(res.status, 415, type);
    }
    const missing = await fetch(`${base}/events`, { method: 'POST', headers: auth, body: new Uint8Array(Buffer.from(JSON.stringify(validEvent()))) });
    assert.equal(missing.status, 415);
  });

  test('application/json with parameters is accepted', async () => {
    const res = await post(validEvent(), { ...auth, 'Content-Type': 'application/json; charset=utf-8' });
    assert.equal(res.status, 201);
  });

  test('a body over 16 KiB returns 413 and is not recorded', async () => {
    const event = { ...validEvent(), padding: 'x'.repeat(16 * 1024) };
    const res = await post(event);
    assert.equal(res.status, 413);
    assert.deepEqual(await res.json(), { error: 'Event too large' });
    assert.ok(!(await listEvents()).some(e => e.eventId === event.eventId));
  });
});

describe('storage failures', () => {
  // Error text that would be dangerous to surface: SQL, file paths, account numbers, the token.
  const leakyError = () => new Error(`SQLITE_FULL: INSERT INTO events (payload) VALUES ('${FROM}') /data/audit.sqlite ${TOKEN}`);

  test('a failed write returns a generic 503 and logs one generic line', async () => {
    mock.method(AuditStore.prototype, 'record', () => { throw leakyError(); });
    const errors = mock.method(console, 'error', () => {});
    const res = await post({ ...validEvent(), ...SENSITIVE_EXTRAS });
    assert.equal(res.status, 503);
    const text = await res.text();
    assert.deepEqual(JSON.parse(text), { error: 'Audit storage unavailable' });
    assert.deepEqual(errors.mock.calls.map(c => c.arguments), [['Audit storage operation failed']]);
    for (const secret of [FROM, TO, TOKEN, 'SQLITE', 'INSERT', '/data/', 'Hunter2-synthetic']) assert.ok(!text.includes(secret));
  });

  test('a failed read returns a generic 503', async () => {
    mock.method(AuditStore.prototype, 'list', () => { throw leakyError(); });
    const errors = mock.method(console, 'error', () => {});
    const res = await fetch(`${base}/events`, { headers: auth });
    assert.equal(res.status, 503);
    assert.deepEqual(await res.json(), { error: 'Audit storage unavailable' });
    assert.equal(errors.mock.callCount(), 1);
  });

  test('after a storage failure the next write succeeds (no stuck state)', async () => {
    const event = validEvent();
    const failing = mock.method(AuditStore.prototype, 'record', () => { throw leakyError(); });
    mock.method(console, 'error', () => {});
    assert.equal((await post(event)).status, 503);
    failing.mock.restore();
    assert.equal((await post(event)).status, 201);
  });
});

describe('shutdown', () => {
  test('SIGTERM closes the HTTP server and the database, then exits 0', async () => {
    const close = mock.method(AuditStore.prototype, 'close');
    const exit = mock.method(process, 'exit', (() => undefined) as never);
    const exited = new Promise<void>(resolve => exit.mock.mockImplementation(((code?: number) => { assert.equal(code, 0); resolve(); }) as never));
    server.closeAllConnections();
    process.emit('SIGTERM');
    await exited;
    assert.equal(server.listening, false);
    assert.equal(close.mock.callCount(), 1);
  });
});
