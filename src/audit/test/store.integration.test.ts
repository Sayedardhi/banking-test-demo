import assert from 'node:assert/strict';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { afterEach, beforeEach, describe, test } from 'node:test';
import { normalizeEvent } from '../src/event.js';
import { AuditStore, EventConflict } from '../src/store.js';
import { tempDir, validInput } from './helpers.js';

const rowCount = (dbPath: string) => {
  const db = new DatabaseSync(dbPath, { readOnly: true });
  try { return (db.prepare('SELECT COUNT(*) AS n FROM events').get() as { n: number }).n; } finally { db.close(); }
};

describe('AuditStore (real SQLite file)', () => {
  let tmp: ReturnType<typeof tempDir>;
  let dbPath: string;
  let store: AuditStore;
  beforeEach(() => { tmp = tempDir(); dbPath = join(tmp.dir, 'audit.sqlite'); store = new AuditStore(dbPath); });
  afterEach(() => { try { store.close(); } catch { /* already closed */ } tmp.cleanup(); });

  test('records an event with a server-generated UTC timestamp', t => {
    t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-01-02T03:04:05.678Z') });
    const result = store.record(normalizeEvent(validInput()));
    assert.equal(result.created, true);
    assert.equal(result.event.recordedAt, '2026-01-02T03:04:05.678Z');
    assert.equal(rowCount(dbPath), 1);
  });

  test('identical replay is idempotent: no new row and original timestamp kept', t => {
    t.mock.timers.enable({ apis: ['Date'], now: new Date('2026-01-02T00:00:00.000Z') });
    store.record(normalizeEvent(validInput()));
    t.mock.timers.setTime(new Date('2026-01-03T00:00:00.000Z').getTime());
    const replay = store.record(normalizeEvent(validInput()));
    assert.equal(replay.created, false);
    assert.equal(replay.event.recordedAt, '2026-01-02T00:00:00.000Z');
    assert.equal(rowCount(dbPath), 1);
  });

  test('same event ID with different content is a conflict and the original is untouched', () => {
    store.record(normalizeEvent(validInput()));
    assert.throws(() => store.record(normalizeEvent(validInput({ amountCents: 10001 }))), EventConflict);
    assert.equal(rowCount(dbPath), 1);
    assert.equal((store.list()[0] as { amountCents: number }).amountCents, 10000);
  });

  test('dedup compares the redacted record (accounts sharing last four digits collide)', () => {
    store.record(normalizeEvent(validInput()));
    const replay = store.record(normalizeEvent(validInput({ fromAccount: '9999996111' })));
    assert.equal(replay.created, false, 'documented limitation: masked accounts cannot be distinguished');
  });

  test('list returns newest first and caps at 100 records', () => {
    for (let i = 1; i <= 101; i++) {
      const eventId = `00000000-0000-4000-8000-${String(i).padStart(12, '0')}`;
      store.record(normalizeEvent(validInput({ eventId, amountCents: i })));
    }
    const events = store.list() as Array<{ amountCents: number }>;
    assert.equal(events.length, 100);
    assert.equal(events[0].amountCents, 101);
    assert.equal(events[99].amountCents, 2);
  });

  test('records persist after the store is closed and reopened', () => {
    store.record(normalizeEvent(validInput()));
    store.close();
    store = new AuditStore(dbPath);
    assert.deepEqual((store.list() as Array<{ eventId: string }>).map(e => e.eventId), ['d7633a92-c4c7-4bdf-bd41-46ed17e1a113']);
  });

  test('persisted payload contains only masked accounts and allowlisted fields', () => {
    store.record(normalizeEvent(validInput({ password: 'hunter2', ssn: '111-22-3333' })));
    const db = new DatabaseSync(dbPath, { readOnly: true });
    const { payload } = db.prepare('SELECT payload FROM events').get() as { payload: string };
    db.close();
    assert.deepEqual(Object.keys(JSON.parse(payload)).sort(), ['action', 'amountCents', 'eventId', 'fromAccount', 'outcome', 'toAccount']);
    for (const secret of ['1011226111', '1033623433', 'hunter2', '111-22-3333']) assert.ok(!payload.includes(secret));
  });

  test('storage failure surfaces as a non-conflict error', () => {
    store.close();
    assert.throws(() => store.record(normalizeEvent(validInput())), (err: unknown) => err instanceof Error && !(err instanceof EventConflict));
  });
});
