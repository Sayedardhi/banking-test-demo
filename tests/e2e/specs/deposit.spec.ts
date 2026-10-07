import { expect, test } from '@playwright/test';
import { auditRecord, deposit, expectAlert, expectDashboard, expectDashboardStays, expectMinimized, expectNoAuditRecord,
  EXTERNAL, LOCAL_ROUTING, masked, signUp } from './helpers';

test.describe('deposit', () => {
  test('credits balance and history, and writes one masked audit record keyed by the browser UUID', async ({ page, request }) => {
    const customer = await signUp(page);

    const { eventId } = await deposit(page, '123.45');

    await expectAlert(page, /\bDeposit successful\s*$/);
    await expectDashboard(page, '$123.45', [/Credit/, new RegExp(EXTERNAL.account), /\+\$123\.45/], 1);
    const record = await auditRecord(request, eventId);
    expect(record).toMatchObject({
      action: 'deposit', outcome: 'succeeded', amountCents: 12345,
      fromAccount: masked(EXTERNAL.account), toAccount: masked(customer.accountNumber),
    });
    expectMinimized(record, [customer.accountNumber, EXTERNAL.account, customer.username, customer.lastName]);
  });

  test('a deposit claiming to come from this bank\'s routing number is rejected with no balance, history or audit change', async ({ page, request }) => {
    await signUp(page);

    const { eventId } = await deposit(page, '75.00', { account: EXTERNAL.account, routing: LOCAL_ROUTING });

    await expectAlert(page, /\bDeposit failed: invalid routing number\s*$/);
    await expectDashboardStays(page, '$0.00', 0);
    await expectNoAuditRecord(request, eventId);
  });
});
