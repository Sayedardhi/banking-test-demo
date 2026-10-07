export class InvalidEvent extends Error {}
export interface AuditEvent {
  eventId: string;
  action: 'payment' | 'deposit';
  outcome: 'succeeded';
  amountCents: number;
  fromAccount: string;
  toAccount: string;
}

// Construct an allowlisted record. Never persist arbitrary request fields.
export function normalizeEvent(input: unknown): AuditEvent {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw new InvalidEvent('Expected an event object');
  }
  const v = input as Record<string, unknown>;
  if (typeof v.eventId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v.eventId)) {
    throw new InvalidEvent('eventId must be a UUID');
  }
  if (v.action !== 'payment' && v.action !== 'deposit') throw new InvalidEvent('Invalid action');
  if (v.outcome !== 'succeeded') throw new InvalidEvent('Invalid outcome');
  if (typeof v.amountCents !== 'number' || !Number.isSafeInteger(v.amountCents) || v.amountCents <= 0) {
    throw new InvalidEvent('amountCents must be a positive safe integer');
  }
  const mask = (account: unknown): string => {
    if (typeof account !== 'string' || !/^\d{10}$/.test(account)) {
      throw new InvalidEvent('Accounts must contain ten digits');
    }
    return '******' + account.slice(-4);
  };
  return {
    eventId: v.eventId.toLowerCase(), action: v.action, outcome: v.outcome,
    amountCents: v.amountCents, fromAccount: mask(v.fromAccount), toAccount: mask(v.toAccount),
  };
}
