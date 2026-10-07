import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { afterEach, describe, mock, test } from 'node:test';
import { normalizeEvent } from '../../src/event.js';
import { AuditStore, EventConflict } from '../../src/store.js';
import { validInput } from '../helpers.js';

// Unit layer: the SQLite boundary (DatabaseSync.prepare) is replaced with a scripted fake so that the
// store's own decisions (dedup, conflict, timestamps, listing) are observed through its interactions.
type Call = { sql: string; method: string; args: unknown[] };
function fakeDatabase(existing?: { payload: string; recorded_at: string }, listRows: unknown[] = []) {
  const calls: Call[] = [];
  mock.method(DatabaseSync.prototype, 'prepare', (sql: string) => ({
    get: (...args: unknown[]) => { calls.push({ sql, method: 'get', args }); return sql.startsWith('SELECT payload') ? existing : { 1: 1 }; },
    run: (...args: unknown[]) => { calls.push({ sql, method: 'run', args }); return { changes: 1, lastInsertRowid: 1 }; },
    all: (...args: unknown[]) => { calls.push({ sql, method: 'all', args }); return listRows; },
  }));
  return calls;
}
const inserts = (calls: Call[]) => calls.filter(c => c.sql.startsWith('INSERT'));
const event = () => normalizeEvent(validInput());

describe('AuditStore decisions (SQLite boundary mocked)', () => {
  let store: AuditStore;
  afterEach(() => { mock.restoreAll(); mock.timers.reset(); store?.close(); });

  test('new event is inserted once with the redacted payload and a server-generated UTC timestamp', () => {
    store = new AuditStore(':memory:');
    const calls = fakeDatabase(undefined);
    mock.timers.enable({ apis: ['Date'], now: new Date('2026-01-02T03:04:05.678Z') });
    const result = store.record(event());
    assert.equal(result.created, true);
    assert.equal(result.event.recordedAt, '2026-01-02T03:04:05.678Z');
    assert.equal(inserts(calls).length, 1);
    const [eventId, payload, recordedAt] = inserts(calls)[0].args as string[];
    assert.equal(eventId, 'd7633a92-c4c7-4bdf-bd41-46ed17e1a113');
    assert.deepEqual(JSON.parse(payload), event());
    assert.equal(recordedAt, '2026-01-02T03:04:05.678Z');
  });

  test('identical replay returns created=false with the ORIGINAL timestamp and performs no insert', () => {
    store = new AuditStore(':memory:');
    const calls = fakeDatabase({ payload: JSON.stringify(event()), recorded_at: '2025-12-31T23:59:59.000Z' });
    mock.timers.enable({ apis: ['Date'], now: new Date('2026-06-01T00:00:00.000Z') });
    const result = store.record(event());
    assert.equal(result.created, false);
    assert.equal(result.event.recordedAt, '2025-12-31T23:59:59.000Z');
    assert.equal(inserts(calls).length, 0);
  });

  test('same event ID with different redacted content throws EventConflict and performs no insert', () => {
    store = new AuditStore(':memory:');
    const calls = fakeDatabase({ payload: JSON.stringify({ ...event(), amountCents: 1 }), recorded_at: '2025-12-31T23:59:59.000Z' });
    assert.throws(() => store.record(event()), EventConflict);
    assert.equal(inserts(calls).length, 0);
  });

  test('lookup is by normalized event ID', () => {
    store = new AuditStore(':memory:');
    const calls = fakeDatabase(undefined);
    store.record(normalizeEvent(validInput({ eventId: 'D7633A92-C4C7-4BDF-BD41-46ED17E1A113' })));
    assert.deepEqual(calls[0].args, ['d7633a92-c4c7-4bdf-bd41-46ed17e1a113']);
  });

  test('list merges stored recordedAt into each payload and asks for newest 100 only', () => {
    store = new AuditStore(':memory:');
    const calls = fakeDatabase(undefined, [{ payload: JSON.stringify(event()), recorded_at: '2026-01-01T00:00:00.000Z' }]);
    assert.deepEqual(store.list(), [{ ...event(), recordedAt: '2026-01-01T00:00:00.000Z' }]);
    assert.match(calls[0].sql, /ORDER BY sequence DESC LIMIT 100/);
  });

  test('storage errors propagate as non-conflict errors (mapped to 503 by the server)', () => {
    store = new AuditStore(':memory:');
    mock.method(DatabaseSync.prototype, 'prepare', () => { throw new Error('SQLITE_IOERR: disk I/O error'); });
    assert.throws(() => store.record(event()), (err: unknown) => err instanceof Error && !(err instanceof EventConflict));
    assert.throws(() => store.ready(), /SQLITE_IOERR/);
  });
});
