import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, mock, test } from 'node:test';
import { normalizeEvent, type AuditEvent } from '../../src/event.js';
import { AuditStore, EventConflict } from '../../src/store.js';
import { validEvent } from '../helpers/fixtures.js';

const event = (overrides = {}): AuditEvent => normalizeEvent(validEvent(overrides));

describe('AuditStore (in-memory SQLite, controlled clock)', () => {
  let store: AuditStore;
  beforeEach(() => {
    mock.timers.enable({ apis: ['Date'], now: Date.parse('2026-03-01T09:30:00.123Z') });
    store = new AuditStore(':memory:');
  });
  afterEach(() => {
    mock.timers.reset();
    try { store.close(); } catch { /* already closed */ }
  });

  test('ready() succeeds on an open database', () => {
    assert.doesNotThrow(() => store.ready());
  });

  test('record() stores a new event with a server-generated UTC ISO-8601 timestamp', () => {
    const e = event();
    const result = store.record(e);
    assert.deepEqual(result, { created: true, event: { ...e, recordedAt: '2026-03-01T09:30:00.123Z' } });
    assert.deepEqual(store.list(), [{ ...e, recordedAt: '2026-03-01T09:30:00.123Z' }]);
  });

  test('an identical retry is not re-recorded and keeps the original timestamp', () => {
    const e = event();
    store.record(e);
    mock.timers.setTime(Date.parse('2026-03-01T10:00:00.000Z'));
    const retry = store.record({ ...e });
    assert.deepEqual(retry, { created: false, event: { ...e, recordedAt: '2026-03-01T09:30:00.123Z' } });
    assert.equal(store.list().length, 1);
  });

  test('same event ID with different content throws EventConflict and leaves the original intact', () => {
    const e = event({ amountCents: 500 });
    store.record(e);
    for (const changed of [{ amountCents: 501 }, { action: 'deposit' as const }, { toAccount: '******0000' }]) {
      assert.throws(() => store.record({ ...e, ...changed }), EventConflict);
    }
    assert.deepEqual(store.list(), [{ ...e, recordedAt: '2026-03-01T09:30:00.123Z' }]);
  });

  test('list() returns newest first and caps the result at 100 records', () => {
    const ids: string[] = [];
    for (let i = 0; i < 101; i++) {
      mock.timers.setTime(Date.parse('2026-03-01T00:00:00.000Z') + i * 1000);
      const e = event({ amountCents: i + 1 });
      ids.push(e.eventId);
      store.record(e);
    }
    const listed = store.list() as Array<AuditEvent & { recordedAt: string }>;
    assert.equal(listed.length, 100);
    assert.equal(listed[0].eventId, ids[100]);
    assert.equal(listed[0].recordedAt, '2026-03-01T00:01:40.000Z');
    assert.equal(listed[99].eventId, ids[1]);
    assert.ok(!listed.some(r => r.eventId === ids[0]), 'oldest record should fall outside the latest 100');
  });

  test('operations on a closed database throw, so the API can map them to 503', () => {
    store.close();
    assert.throws(() => store.ready());
    assert.throws(() => store.record(event()));
    assert.throws(() => store.list());
  });
});
