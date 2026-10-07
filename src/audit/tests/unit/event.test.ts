import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { InvalidEvent, normalizeEvent } from '../../src/event.js';
import { ALLOWLIST, RAW_FROM, RAW_TO, sensitiveExtras, sensitiveValues, validInput } from '../helpers.js';

const rejects = (input: unknown, message: RegExp) =>
  assert.throws(() => normalizeEvent(input), (err: unknown) => err instanceof InvalidEvent && message.test(err.message));

describe('normalizeEvent: accepted events are normalized to the allowlisted, masked record', () => {
  test('payment keeps only defined fields and masks both accounts to the last four digits', () => {
    assert.deepEqual(normalizeEvent(validInput()), {
      eventId: 'd7633a92-c4c7-4bdf-bd41-46ed17e1a113',
      action: 'payment',
      outcome: 'succeeded',
      amountCents: 10000,
      fromAccount: '******6111',
      toAccount: '******3433',
    });
  });

  test('deposit with the smallest positive amount is accepted', () => {
    const event = normalizeEvent(validInput({ action: 'deposit', amountCents: 1 }));
    assert.equal(event.action, 'deposit');
    assert.equal(event.amountCents, 1);
  });

  test('largest safe integer amount is accepted unchanged', () => {
    assert.equal(normalizeEvent(validInput({ amountCents: Number.MAX_SAFE_INTEGER })).amountCents, Number.MAX_SAFE_INTEGER);
  });

  test('event IDs are lower-cased so case variants of one UUID deduplicate', () => {
    assert.equal(normalizeEvent(validInput({ eventId: 'D7633A92-C4C7-4BDF-BD41-46ED17E1A113' })).eventId,
      'd7633a92-c4c7-4bdf-bd41-46ed17e1a113');
  });

  test('masked account is exactly six asterisks plus last four digits (no other digits retained)', () => {
    const event = normalizeEvent(validInput({ fromAccount: '0000000000', toAccount: '9876543210' }));
    assert.equal(event.fromAccount, '******0000');
    assert.equal(event.toAccount, '******3210');
    assert.equal(event.toAccount.length, 10);
  });

  test('password, token, SSN, email and nested metadata are dropped, never copied', () => {
    const event = normalizeEvent(validInput(sensitiveExtras));
    assert.deepEqual(Object.keys(event).sort(), ALLOWLIST);
    const serialized = JSON.stringify(event);
    for (const secret of [...sensitiveValues, RAW_FROM, RAW_TO]) {
      assert.ok(!serialized.includes(secret), `normalized event leaked ${secret}`);
    }
  });

  test('client-supplied recordedAt, sequence and id fields are ignored', () => {
    const event = normalizeEvent(validInput({ recordedAt: '1999-01-01T00:00:00Z', sequence: 1, id: 7 }));
    assert.deepEqual(Object.keys(event).sort(), ALLOWLIST);
  });

  test('a JSON "__proto__" key is not carried into the record', () => {
    const input = JSON.parse(JSON.stringify(validInput()).replace(/^\{/, '{"__proto__":{"ssn":"111-22-3333"},'));
    const event = normalizeEvent(input);
    assert.deepEqual(Object.keys(event).sort(), ALLOWLIST);
    assert.equal((event as unknown as Record<string, unknown>).ssn, undefined);
  });

  test('normalization does not mutate the caller input', () => {
    const input = validInput();
    const copy = structuredClone(input);
    normalizeEvent(input);
    assert.deepEqual(input, copy);
  });
});

describe('normalizeEvent: rejected events raise InvalidEvent (mapped to HTTP 400)', () => {
  for (const input of [null, undefined, 'event', 42, true, [validInput()]]) {
    test(`non-object input ${JSON.stringify(input)} is rejected`, () => rejects(input, /Expected an event object/));
  }

  test('empty object is rejected at the first required field', () => rejects({}, /eventId must be a UUID/));

  for (const eventId of [undefined, null, '', 'not-a-uuid', 'd7633a92c4c74bdfbd4146ed17e1a113',
    'd7633a92-c4c7-4bdf-bd41-46ed17e1a11', 'g7633a92-c4c7-4bdf-bd41-46ed17e1a113', ' d7633a92-c4c7-4bdf-bd41-46ed17e1a113',
    'd7633a92-c4c7-4bdf-bd41-46ed17e1a113\n', '{d7633a92-c4c7-4bdf-bd41-46ed17e1a113}', 12345]) {
    test(`eventId ${JSON.stringify(eventId)} is rejected`, () => rejects(validInput({ eventId }), /eventId must be a UUID/));
  }

  for (const action of [undefined, null, 'withdrawal', 'transfer', 'PAYMENT', '', ['payment']]) {
    test(`action ${JSON.stringify(action)} is rejected`, () => rejects(validInput({ action }), /Invalid action/));
  }

  for (const outcome of [undefined, null, 'failed', 'pending', 'SUCCEEDED', true]) {
    test(`outcome ${JSON.stringify(outcome)} is rejected (failed transactions are not audited)`, () =>
      rejects(validInput({ outcome }), /Invalid outcome/));
  }

  for (const amountCents of [undefined, null, 0, -0, -1, 1.5, 0.01, Number.NaN, Number.POSITIVE_INFINITY,
    Number.MAX_SAFE_INTEGER + 1, '100', true, [100], { value: 100 }]) {
    test(`amountCents ${typeof amountCents === 'object' ? JSON.stringify(amountCents) : String(amountCents)} is rejected`, () =>
      rejects(validInput({ amountCents }), /amountCents must be a positive safe integer/));
  }

  for (const field of ['fromAccount', 'toAccount']) {
    for (const account of [undefined, null, '', '101122611', '10112261111', '10112261a1', ' 1011226111', '1011226111 ',
      '101-122-6111', '\u0661\u0660\u0661\u0661\u0662\u0662\u0666\u0661\u0661\u0661', 1011226111, ['1011226111']]) {
      test(`${field} ${JSON.stringify(account)} is rejected`, () =>
        rejects(validInput({ [field]: account }), /Accounts must contain ten digits/));
    }
  }

  test('validation error messages never echo the submitted account number', () => {
    assert.throws(() => normalizeEvent(validInput({ toAccount: '10336234339' })),
      (err: unknown) => err instanceof InvalidEvent && !err.message.includes('1033623433'));
  });
});
