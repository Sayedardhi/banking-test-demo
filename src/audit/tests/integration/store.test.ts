import assert from 'node:assert/strict';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { afterEach, beforeEach, describe, test } from 'node:test';
import { normalizeEvent } from '../../src/event.js';
import { AuditStore, EventConflict } from '../../src/store.js';
import { ALLOWLIST, dumpDir, RAW_FROM, RAW_TO, rows, sensitiveExtras, sensitiveValues, tempDir, uuid, validInput } from '../helpers.js';

describe('AuditStore against a real temporary SQLite file', () => {
  let tmp: ReturnType<typeof tempDir>;
  let dbPath: string;
  let store: AuditStore;
  beforeEach(() => { tmp = tempDir(); dbPath = join(tmp.dir, 'audit.sqlite'); store = new AuditStore(dbPath); });
  afterEach(() => { try { store.close(); } catch { /* already closed */ } tmp.cleanup(); });

  test('records one row with a server-generated UTC timestamp', t => {
    t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-01-02T03:04:05.678Z') });
    const result = store.record(normalizeEvent(validInput()));
    assert.equal(result.created, true);
    assert.equal(result.event.recordedAt, '2026-01-02T03:04:05.678Z');
    const stored = rows(dbPath);
    assert.equal(stored.length, 1);
    assert.equal(stored[0].event_id, 'd7633a92-c4c7-4bdf-bd41-46ed17e1a113');
    assert.equal(stored[0].recorded_at, '2026-01-02T03:04:05.678Z');
  });

  test('identical replay is idempotent: no new row and original timestamp kept', t => {
    t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-01-02T00:00:00.000Z') });
    store.record(normalizeEvent(validInput()));
    t.mock.timers.setTime(new Date('2026-01-03T00:00:00.000Z').getTime());
    const replay = store.record(normalizeEvent(validInput({ eventId: 'D7633A92-C4C7-4BDF-BD41-46ED17E1A113' })));
    assert.equal(replay.created, false);
    assert.equal(replay.event.recordedAt, '2026-01-02T00:00:00.000Z');
    assert.equal(rows(dbPath).length, 1);
  });

  for (const [field, value] of [['amountCents', 10001], ['action', 'deposit'], ['toAccount', '1033629999'], ['fromAccount', '1011220000']] as const) {
    test(`same event ID with different ${field} is a conflict and the original row is untouched`, () => {
      store.record(normalizeEvent(validInput()));
      const before = rows(dbPath);
      assert.throws(() => store.record(normalizeEvent(validInput({ [field]: value }))), EventConflict);
      assert.deepEqual(rows(dbPath), before);
    });
  }

  test('documented limitation: dedup compares the redacted record, so accounts sharing last four digits collide', () => {
    store.record(normalizeEvent(validInput()));
    const replay = store.record(normalizeEvent(validInput({ fromAccount: '9999996111' })));
    assert.equal(replay.created, false);
    assert.equal(rows(dbPath).length, 1);
  });

  test('database UNIQUE constraint rejects a second row for an event ID even if the application check is bypassed', () => {
    store.record(normalizeEvent(validInput()));
    const db = new DatabaseSync(dbPath);
    try {
      assert.throws(() => db.prepare('INSERT INTO events (event_id, payload, recorded_at) VALUES (?, ?, ?)')
        .run('d7633a92-c4c7-4bdf-bd41-46ed17e1a113', '{}', '2026-01-01T00:00:00.000Z'), /UNIQUE/);
    } finally { db.close(); }
    assert.equal(rows(dbPath).length, 1);
  });

  test('list returns newest first and caps at 100 records', () => {
    for (let i = 1; i <= 101; i++) store.record(normalizeEvent(validInput({ eventId: uuid(i), amountCents: i })));
    const events = store.list() as Array<{ amountCents: number; recordedAt: string }>;
    assert.equal(events.length, 100);
    assert.equal(events[0].amountCents, 101);
    assert.equal(events[99].amountCents, 2);
    assert.ok(events.every(e => /Z$/.test(e.recordedAt)));
  });

  test('records persist after the store is closed and reopened (schema creation is idempotent)', () => {
    store.record(normalizeEvent(validInput()));
    store.close();
    store = new AuditStore(dbPath);
    assert.deepEqual((store.list() as Array<{ eventId: string }>).map(e => e.eventId), ['d7633a92-c4c7-4bdf-bd41-46ed17e1a113']);
  });

  test('persisted payload has only allowlisted fields; no raw accounts or sensitive values in any database file', () => {
    store.record(normalizeEvent(validInput(sensitiveExtras)));
    const [{ payload }] = rows(dbPath, 'SELECT payload FROM events') as Array<{ payload: string }>;
    assert.deepEqual(Object.keys(JSON.parse(payload)).sort(), ALLOWLIST);
    const files = dumpDir(tmp.dir);
    for (const secret of [RAW_FROM, RAW_TO, ...sensitiveValues]) assert.ok(!files.includes(secret), `database files leaked ${secret}`);
    assert.ok(files.includes('******6111'));
  });

  test('storage failure after close surfaces as a non-conflict error and writes nothing', () => {
    store.close();
    assert.throws(() => store.record(normalizeEvent(validInput())), (err: unknown) => err instanceof Error && !(err instanceof EventConflict));
    assert.equal(rows(dbPath).length, 0);
  });
});
