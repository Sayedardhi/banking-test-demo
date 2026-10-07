import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { InvalidEvent, normalizeEvent } from '../src/event.js';
import { validInput } from './helpers.js';

const rejects = (input: unknown, message: RegExp) =>
  assert.throws(() => normalizeEvent(input), (err: unknown) => err instanceof InvalidEvent && message.test(err.message));

describe('normalizeEvent: accepted events', () => {
  test('payment is normalized to the allowlisted, masked record', () => {
    assert.deepEqual(normalizeEvent(validInput()), {
      eventId: 'd7633a92-c4c7-4bdf-bd41-46ed17e1a113',
      action: 'payment',
      outcome: 'succeeded',
      amountCents: 10000,
      fromAccount: '******6111',
      toAccount: '******3433',
    });
  });

  test('deposit and the smallest positive amount are accepted', () => {
    const event = normalizeEvent(validInput({ action: 'deposit', amountCents: 1 }));
    assert.equal(event.action, 'deposit');
    assert.equal(event.amountCents, 1);
  });

  test('largest safe integer amount is accepted', () => {
    assert.equal(normalizeEvent(validInput({ amountCents: Number.MAX_SAFE_INTEGER })).amountCents, Number.MAX_SAFE_INTEGER);
  });

  test('event IDs are lower-cased so case variants deduplicate', () => {
    assert.equal(normalizeEvent(validInput({ eventId: 'D7633A92-C4C7-4BDF-BD41-46ED17E1A113' })).eventId,
      'd7633a92-c4c7-4bdf-bd41-46ed17e1a113');
  });

  test('sensitive and unknown fields are dropped, never copied', () => {
    const event = normalizeEvent(validInput({
      password: 'hunter2', token: 'eyJhbGciOi', ssn: '111-22-3333', email: 'a@example.com',
      metadata: { nested: { ssn: '111-22-3333' } }, recordedAt: '1999-01-01T00:00:00Z',
    }));
    assert.deepEqual(Object.keys(event).sort(), ['action', 'amountCents', 'eventId', 'fromAccount', 'outcome', 'toAccount']);
    const serialized = JSON.stringify(event);
    for (const secret of ['hunter2', 'eyJhbGciOi', '111-22-3333', 'a@example.com', '1999', '1011226111', '1033623433']) {
      assert.ok(!serialized.includes(secret), `normalized event leaked ${secret}`);
    }
  });
});

describe('normalizeEvent: rejected events', () => {
  for (const input of [null, undefined, 'event', 42, [validInput()]]) {
    test(`non-object input ${JSON.stringify(input)} is rejected`, () => rejects(input, /Expected an event object/));
  }

  for (const eventId of [undefined, null, '', 'not-a-uuid', 'd7633a92c4c74bdfbd4146ed17e1a113',
    'd7633a92-c4c7-4bdf-bd41-46ed17e1a11', 'g7633a92-c4c7-4bdf-bd41-46ed17e1a113', 12345]) {
    test(`eventId ${JSON.stringify(eventId)} is rejected`, () => rejects(validInput({ eventId }), /eventId must be a UUID/));
  }

  for (const action of [undefined, 'withdrawal', 'PAYMENT', '']) {
    test(`action ${JSON.stringify(action)} is rejected`, () => rejects(validInput({ action }), /Invalid action/));
  }

  for (const outcome of [undefined, 'failed', 'SUCCEEDED']) {
    test(`outcome ${JSON.stringify(outcome)} is rejected`, () => rejects(validInput({ outcome }), /Invalid outcome/));
  }

  for (const amountCents of [undefined, null, 0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY,
    Number.MAX_SAFE_INTEGER + 1, '100']) {
    test(`amountCents ${String(amountCents)} is rejected`, () =>
      rejects(validInput({ amountCents }), /amountCents must be a positive safe integer/));
  }

  for (const field of ['fromAccount', 'toAccount']) {
    for (const account of [undefined, null, '101122611', '10112261111', '10112261a1', ' 1011226111', 1011226111]) {
      test(`${field} ${JSON.stringify(account)} is rejected`, () =>
        rejects(validInput({ [field]: account }), /Accounts must contain ten digits/));
    }
  }
});
