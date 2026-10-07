import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { InvalidEvent, normalizeEvent } from '../../src/event.js';
import { FROM, SENSITIVE_EXTRAS, SENSITIVE_STRINGS, TO, validEvent } from '../helpers/fixtures.js';

const rejects = (input: unknown, message: RegExp) =>
  assert.throws(() => normalizeEvent(input), (err: unknown) => err instanceof InvalidEvent && message.test(err.message));

describe('normalizeEvent: accepted events', () => {
  test('payment and deposit events are normalized to exactly the allowlisted fields with masked accounts', () => {
    for (const action of ['payment', 'deposit'] as const) {
      const input = validEvent({ action, eventId: 'd7633a92-c4c7-4bdf-bd41-46ed17e1a113', amountCents: 10_000 });
      assert.deepEqual(normalizeEvent(input), {
        eventId: 'd7633a92-c4c7-4bdf-bd41-46ed17e1a113', action, outcome: 'succeeded',
        amountCents: 10_000, fromAccount: '******6111', toAccount: '******3433',
      });
    }
  });

  test('masking keeps only the last four digits of each ten-digit account', () => {
    const event = normalizeEvent(validEvent({ fromAccount: '0000000001', toAccount: '9999999990' }));
    assert.equal(event.fromAccount, '******0001');
    assert.equal(event.toAccount, '******9990');
  });

  test('upper-case event IDs are lower-cased so retries with different casing deduplicate', () => {
    const event = normalizeEvent(validEvent({ eventId: 'D7633A92-C4C7-4BDF-BD41-46ED17E1A113' }));
    assert.equal(event.eventId, 'd7633a92-c4c7-4bdf-bd41-46ed17e1a113');
  });

  test('amount boundaries: 1 cent and Number.MAX_SAFE_INTEGER cents are accepted', () => {
    assert.equal(normalizeEvent(validEvent({ amountCents: 1 })).amountCents, 1);
    assert.equal(normalizeEvent(validEvent({ amountCents: Number.MAX_SAFE_INTEGER })).amountCents, Number.MAX_SAFE_INTEGER);
  });

  test('password, token, SSN, email, nested metadata and client timestamps are dropped, not copied', () => {
    const event = normalizeEvent({ ...validEvent(), ...SENSITIVE_EXTRAS });
    assert.deepEqual(Object.keys(event).sort(), ['action', 'amountCents', 'eventId', 'fromAccount', 'outcome', 'toAccount']);
    const serialized = JSON.stringify(event);
    for (const secret of SENSITIVE_STRINGS) assert.ok(!serialized.includes(secret), `normalized event leaked ${secret}`);
  });

  test('a "__proto__" key in parsed JSON cannot supply event fields', () => {
    const input = JSON.parse(`{"__proto__": ${JSON.stringify(validEvent())}}`);
    rejects(input, /eventId must be a UUID/);
  });
});

describe('normalizeEvent: rejected events (400 at the API)', () => {
  const cases: Array<[string, unknown, RegExp]> = [
    ['null body', null, /Expected an event object/],
    ['array body', [validEvent()], /Expected an event object/],
    ['string body', 'payment', /Expected an event object/],
    ['number body', 42, /Expected an event object/],
    ['missing eventId', { ...validEvent(), eventId: undefined }, /eventId must be a UUID/],
    ['null eventId', validEvent({ eventId: null as unknown as string }), /eventId must be a UUID/],
    ['numeric eventId', validEvent({ eventId: 123 as unknown as string }), /eventId must be a UUID/],
    ['eventId without hyphens', validEvent({ eventId: 'd7633a92c4c74bdfbd4146ed17e1a113' }), /eventId must be a UUID/],
    ['eventId with non-hex', validEvent({ eventId: 'g7633a92-c4c7-4bdf-bd41-46ed17e1a113' }), /eventId must be a UUID/],
    ['eventId with trailing newline', validEvent({ eventId: 'd7633a92-c4c7-4bdf-bd41-46ed17e1a113\n' }), /eventId must be a UUID/],
    ['eventId wrapped in braces', validEvent({ eventId: '{d7633a92-c4c7-4bdf-bd41-46ed17e1a113}' }), /eventId must be a UUID/],
    ['unsupported action', validEvent({ action: 'withdrawal' }), /Invalid action/],
    ['action with different case', validEvent({ action: 'Payment' }), /Invalid action/],
    ['missing action', { ...validEvent(), action: undefined }, /Invalid action/],
    ['failed outcome', validEvent({ outcome: 'failed' }), /Invalid outcome/],
    ['missing outcome', { ...validEvent(), outcome: undefined }, /Invalid outcome/],
    ['zero amount', validEvent({ amountCents: 0 }), /amountCents/],
    ['negative zero amount', validEvent({ amountCents: -0 }), /amountCents/],
    ['negative amount', validEvent({ amountCents: -1 }), /amountCents/],
    ['fractional cents', validEvent({ amountCents: 10.5 }), /amountCents/],
    ['amount above MAX_SAFE_INTEGER', validEvent({ amountCents: Number.MAX_SAFE_INTEGER + 1 }), /amountCents/],
    ['NaN amount', validEvent({ amountCents: Number.NaN }), /amountCents/],
    ['Infinity amount', validEvent({ amountCents: Number.POSITIVE_INFINITY }), /amountCents/],
    ['amount as numeric string', validEvent({ amountCents: '10000' as unknown as number }), /amountCents/],
    ['null amount', validEvent({ amountCents: null as unknown as number }), /amountCents/],
    ['nine-digit fromAccount', validEvent({ fromAccount: '101122611' }), /ten digits/],
    ['eleven-digit toAccount', validEvent({ toAccount: '10336234331' }), /ten digits/],
    ['account with letters', validEvent({ fromAccount: '10112261AB' }), /ten digits/],
    ['account with spaces', validEvent({ toAccount: '1033 623433' }), /ten digits/],
    ['account with trailing newline', validEvent({ fromAccount: `${FROM}\n` }), /ten digits/],
    ['account as number', validEvent({ toAccount: 1033623433 as unknown as string }), /ten digits/],
    ['full-width digits', validEvent({ fromAccount: '１０１１２２６１１１' }), /ten digits/],
    ['missing toAccount', { ...validEvent(), toAccount: undefined }, /ten digits/],
    ['already-masked account', validEvent({ fromAccount: '******6111' }), /ten digits/],
  ];
  for (const [name, input, message] of cases) {
    test(`rejects ${name}`, () => rejects(input, message));
  }

  test('validation failures never echo the submitted account number', () => {
    try {
      normalizeEvent(validEvent({ toAccount: `${TO}9` }));
      assert.fail('expected InvalidEvent');
    } catch (err) {
      assert.ok(err instanceof InvalidEvent);
      assert.ok(!err.message.includes(TO));
    }
  });
});
