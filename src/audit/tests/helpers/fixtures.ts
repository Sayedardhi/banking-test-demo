import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/** Synthetic accounts only. Last four digits drive the masked form. */
export const FROM = '1011226111';
export const TO = '1033623433';

export interface EventInput {
  eventId: string;
  action: string;
  outcome: string;
  amountCents: number;
  fromAccount: string;
  toAccount: string;
  [extra: string]: unknown;
}

export function validEvent(overrides: Partial<EventInput> = {}): EventInput {
  return {
    eventId: randomUUID(), action: 'payment', outcome: 'succeeded',
    amountCents: 10_000, fromAccount: FROM, toAccount: TO, ...overrides,
  };
}

/** Fields a caller might leak into an audit request; none may ever be persisted or echoed. */
export const SENSITIVE_EXTRAS = {
  password: 'Hunter2-synthetic',
  token: 'eyJhbGciOiJSUzI1NiJ9.synthetic.jwt',
  ssn: '123-45-6789',
  email: 'synthetic.customer@example.test',
  metadata: { nested: { cardNumber: '4111111111111111' } },
  recordedAt: '1999-12-31T23:59:59.000Z',
};

export const SENSITIVE_STRINGS = [
  FROM, TO, 'Hunter2-synthetic', 'eyJhbGciOiJSUzI1NiJ9.synthetic.jwt', '123-45-6789',
  'synthetic.customer@example.test', '4111111111111111', '1999-12-31T23:59:59.000Z', 'cardNumber',
];

export function tempDir(prefix = 'audit-test-'): { dir: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  return { dir, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}
