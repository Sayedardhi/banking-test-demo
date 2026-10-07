import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { request as httpRequest } from 'node:http';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { after, before, describe, test } from 'node:test';
import { FROM, SENSITIVE_EXTRAS, SENSITIVE_STRINGS, TO, tempDir, validEvent } from '../helpers/fixtures.js';
import { startServer, type RunningServer } from '../helpers/server-process.js';

// Real boundary: compiled server process <-> HTTP <-> SQLite file in a per-run temp directory.
const TOKEN = 'integration-token-7f3a';
const auth = { Authorization: `Bearer ${TOKEN}` };
const json = { ...auth, 'Content-Type': 'application/json' };

let tmp: ReturnType<typeof tempDir>;
let dbPath: string;
let server: RunningServer;

before(async () => {
  tmp = tempDir();
  dbPath = join(tmp.dir, 'audit.sqlite');
  server = await startServer({ AUDIT_TOKEN: TOKEN, AUDIT_DB_PATH: dbPath });
});
after(async () => { await server?.stop(); tmp?.cleanup(); });

const post = (body: unknown, headers: Record<string, string> = json) =>
  fetch(`${server.baseUrl}/events`, { method: 'POST', headers, body: typeof body === 'string' ? body : JSON.stringify(body) });

/** Reads the database file directly (independent of the API) to verify persisted state. */
function rows(eventId?: string): Array<{ event_id: string; payload: string; recorded_at: string }> {
  const db = new DatabaseSync(dbPath, { readOnly: true });
  try {
    const sql = 'SELECT event_id, payload, recorded_at FROM events' + (eventId ? ' WHERE event_id = ?' : '') + ' ORDER BY sequence';
    return (eventId ? db.prepare(sql).all(eventId) : db.prepare(sql).all()) as never;
  } finally { db.close(); }
}
const rowCount = () => rows().length;

describe('ingestion persists exactly the minimized record', () => {
  test('a payment is stored once, masked, with only the allowlisted fields and a server UTC timestamp', async () => {
    const event = { ...validEvent({ amountCents: 4321 }), ...SENSITIVE_EXTRAS };
    const before = Date.now();
    const res = await post(event);
    assert.equal(res.status, 201);
    const stored = rows(event.eventId);
    assert.equal(stored.length, 1);
    assert.deepEqual(JSON.parse(stored[0].payload), {
      eventId: event.eventId, action: 'payment', outcome: 'succeeded', amountCents: 4321,
      fromAccount: '******6111', toAccount: '******3433',
    });
    assert.match(stored[0].recorded_at, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    assert.notEqual(stored[0].recorded_at, SENSITIVE_EXTRAS.recordedAt);
    const recorded = Date.parse(stored[0].recorded_at);
    assert.ok(recorded >= before - 1 && recorded <= Date.now() + 1, 'recordedAt must be the server clock');
  });

  test('raw account numbers and sensitive extras never reach the database files on disk', async () => {
    await post({ ...validEvent({ action: 'deposit' }), ...SENSITIVE_EXTRAS });
    const files = [dbPath, `${dbPath}-wal`].filter(existsSync).map(f => readFileSync(f).toString('latin1'));
    assert.ok(files.length > 0);
    for (const secret of SENSITIVE_STRINGS) {
      for (const bytes of files) assert.ok(!bytes.includes(secret), `"${secret}" found in SQLite files`);
    }
  });

  test('GET /events returns the stored records without raw identifiers', async () => {
    const res = await fetch(`${server.baseUrl}/events`, { headers: auth });
    assert.equal(res.status, 200);
    const text = await res.text();
    for (const secret of [FROM, TO, 'Hunter2-synthetic', '123-45-6789']) assert.ok(!text.includes(secret));
    const { events } = JSON.parse(text);
    assert.equal(events.length, rowCount());
    for (const e of events) {
      assert.deepEqual(Object.keys(e).sort(), ['action', 'amountCents', 'eventId', 'fromAccount', 'outcome', 'recordedAt', 'toAccount']);
    }
  });
});

describe('rejected requests leave the database unchanged', () => {
  const cases: Array<[string, () => Promise<Response>, number]> = [
    ['missing token', () => post(validEvent(), { 'Content-Type': 'application/json' }), 401],
    ['wrong token', () => post(validEvent(), { Authorization: 'Bearer wrong', 'Content-Type': 'application/json' }), 401],
    ['invalid account', () => post(validEvent({ toAccount: '12345' })), 400],
    ['failed outcome', () => post(validEvent({ outcome: 'failed' })), 400],
    ['string amount', () => post(validEvent({ amountCents: '100' as unknown as number })), 400],
    ['null amount', () => post(validEvent({ amountCents: null as unknown as number })), 400],
    ['JSON null', () => post('null'), 400],
    ['truncated JSON', () => post('{"eventId": "d7633a92-'), 400],
    ['text/plain', () => post(validEvent(), { ...auth, 'Content-Type': 'text/plain' }), 415],
    ['DELETE', () => fetch(`${server.baseUrl}/events`, { method: 'DELETE', headers: auth }), 405],
    ['PUT', () => fetch(`${server.baseUrl}/events`, { method: 'PUT', headers: json, body: JSON.stringify(validEvent()) }), 405],
    ['unknown route', () => fetch(`${server.baseUrl}/events/1`, { method: 'POST', headers: json, body: JSON.stringify(validEvent()) }), 404],
  ];
  for (const [name, send, status] of cases) {
    test(`${name} -> ${status}, no row written`, async () => {
      const before = rowCount();
      const res = await send();
      assert.equal(res.status, status);
      assert.ok((await res.json()).error);
      assert.equal(rowCount(), before);
    });
  }

  test('a media type differing only in letter case (Application/JSON) is accepted like application/json', async () => {
    // RFC 9110 §8.3.1: type and subtype names are case-insensitive, so this is a supported media type.
    const event = validEvent();
    const res = await post(event, { ...auth, 'Content-Type': 'Application/JSON' });
    assert.equal(res.status, 201);
    assert.equal(rows(event.eventId).length, 1);
  });
});

describe('payload size limit (16 KiB) over a real socket', () => {
  const padded = (bytes: number) => {
    const event = validEvent();
    const base = JSON.stringify({ ...event, pad: '' }).length;
    const body = JSON.stringify({ ...event, pad: 'x'.repeat(bytes - base) });
    assert.equal(Buffer.byteLength(body), bytes);
    return { event, body };
  };

  test('exactly 16384 bytes is accepted and stored without the padding', async () => {
    const { event, body } = padded(16384);
    assert.equal((await post(body)).status, 201);
    const stored = rows(event.eventId);
    assert.equal(stored.length, 1);
    assert.ok(!stored[0].payload.includes('pad'));
  });

  test('16385 bytes returns 413 and nothing is stored', async () => {
    const { event, body } = padded(16385);
    const res = await post(body);
    assert.equal(res.status, 413);
    assert.deepEqual(await res.json(), { error: 'Event too large' });
    assert.equal(rows(event.eventId).length, 0);
  });

  test('a chunked body without Content-Length is also capped at 16 KiB', async () => {
    const event = validEvent();
    const status = await new Promise<number>((resolve, reject) => {
      const req = httpRequest(`${server.baseUrl}/events`, { method: 'POST', headers: { ...json, 'Transfer-Encoding': 'chunked' } }, res => {
        res.resume(); resolve(res.statusCode!);
      });
      req.on('error', reject);
      req.write(JSON.stringify({ ...event, pad: '' }).slice(0, -2));
      for (let i = 0; i < 20; i++) req.write(`${'y'.repeat(1024)}`);
      req.end('"}');
    });
    assert.equal(status, 413);
    assert.equal(rows(event.eventId).length, 0);
  });
});

describe('idempotency and append-only behavior', () => {
  test('an identical retry (any ID casing, any key order) returns 200 and writes no second row', async () => {
    const event = validEvent();
    const first = await (await post(event)).json();
    const reordered = {
      toAccount: event.toAccount, fromAccount: event.fromAccount, amountCents: event.amountCents,
      outcome: event.outcome, action: event.action, eventId: event.eventId.toUpperCase(), extra: 'ignored',
    };
    const retry = await post(reordered);
    assert.equal(retry.status, 200);
    assert.deepEqual(await retry.json(), { created: false, event: first.event });
    assert.equal(rows(event.eventId).length, 1);
  });

  test('a conflicting reuse of an event ID returns 409 and the original row is untouched', async () => {
    const event = validEvent({ amountCents: 700 });
    await post(event);
    const original = rows(event.eventId);
    for (const change of [{ amountCents: 701 }, { action: 'deposit' }, { toAccount: '1033620000' }]) {
      const res = await post({ ...event, ...change });
      assert.equal(res.status, 409, JSON.stringify(change));
    }
    assert.deepEqual(rows(event.eventId), original);
  });

  test('documented limitation: different raw accounts sharing the last four digits deduplicate as the same event', async () => {
    const event = validEvent({ fromAccount: '1111116111' });
    await post(event);
    const res = await post({ ...event, fromAccount: '2222226111' });
    assert.equal(res.status, 200);
    assert.equal(rows(event.eventId).length, 1);
  });

  test('concurrent identical submissions create exactly one row', async () => {
    const event = validEvent();
    const statuses = (await Promise.all(Array.from({ length: 10 }, () => post(event)))).map(r => r.status).sort();
    assert.deepEqual(statuses, [200, 200, 200, 200, 200, 200, 200, 200, 200, 201]);
    assert.equal(rows(event.eventId).length, 1);
  });
});

describe('GET /events window', () => {
  test('returns at most the latest 100 records, newest first, matching database order', async () => {
    for (let i = 0; i < 101; i++) assert.equal((await post(validEvent({ amountCents: i + 1 }))).status, 201);
    const { events } = await (await fetch(`${server.baseUrl}/events`, { headers: auth })).json();
    const latest = rows().slice(-100).reverse().map(r => r.event_id);
    assert.equal(events.length, 100);
    assert.deepEqual(events.map((e: { eventId: string }) => e.eventId), latest);
  });
});

describe('logging', () => {
  test('normal operation logs no event content, accounts or credentials', () => {
    const log = server.output();
    assert.match(log, /Audit service ready/);
    for (const secret of [...SENSITIVE_STRINGS, TOKEN]) assert.ok(!log.includes(secret), `log leaked ${secret}`);
  });
});
