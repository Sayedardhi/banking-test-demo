import { expect, test, type Browser } from '@playwright/test';
import { auditRecord, auditRecords, deposit, expectAlert, expectDashboard, expectDashboardStays, expectMinimized,
  expectNoAuditRecord, masked, pay, signUp } from './helpers';

/** Each customer gets an isolated browser context (own cookies/session). */
async function customerIn(browser: Browser) {
  const context = await browser.newContext();
  const page = await context.newPage();
  return { context, page, customer: await signUp(page) };
}

test.describe('payment between two customers', () => {
  test('debits the sender, credits the recipient, and writes one masked audit record', async ({ browser, page, request }) => {
    const recipient = await customerIn(browser);
    const sender = await signUp(page);
    await deposit(page, '100.00');
    await expectDashboard(page, '$100.00', undefined, 1);

    const { eventId } = await pay(page, recipient.customer.accountNumber, '25.50');

    await expectAlert(page, /\bPayment successful\s*$/);
    await expectDashboard(page, '$74.50', [/Debit/, new RegExp(recipient.customer.accountNumber), /-\$25\.50/], 2);
    await expectDashboard(recipient.page, '$25.50', [/Credit/, new RegExp(sender.accountNumber), /\+\$25\.50/], 1);
    const record = await auditRecord(request, eventId);
    expect(record).toMatchObject({
      action: 'payment', outcome: 'succeeded', amountCents: 2550,
      fromAccount: masked(sender.accountNumber), toAccount: masked(recipient.customer.accountNumber),
    });
    expectMinimized(record, [sender.accountNumber, recipient.customer.accountNumber, sender.username, recipient.customer.username]);
    await recipient.context.close();
  });

  test('overdraft that bypasses the browser limit is rejected by the server: no money moves and nothing is audited', async ({ browser, page, request }) => {
    const recipient = await customerIn(browser);
    await signUp(page);
    await deposit(page, '10.00');
    await expectDashboard(page, '$10.00', undefined, 1);

    const { eventId } = await pay(page, recipient.customer.accountNumber, '10.01', { bypassClientLimits: true });

    await expectAlert(page, /\bPayment failed: .*insufficient balance\s*$/i);
    await expectDashboardStays(page, '$10.00', 1);
    await expectDashboardStays(recipient.page, '$0.00', 0);
    await expectNoAuditRecord(request, eventId);
    await recipient.context.close();
  });

  test('a negative payment posted around the browser checks cannot pull money from the recipient', async ({ browser, page, request }) => {
    const recipient = await customerIn(browser);
    await deposit(recipient.page, '40.00');
    await expectDashboard(recipient.page, '$40.00', undefined, 1);
    await signUp(page);
    const eventId = crypto.randomUUID();

    // index.js blocks amounts <= 0 in the browser, so post the form directly with the signed-in session.
    const res = await page.request.post('/payment', {
      form: { account_num: 'add', contact_account_num: recipient.customer.accountNumber, amount: '-15.00', uuid: eventId },
      maxRedirects: 0,
    });

    expect(res.status()).toBe(302);
    expect(decodeURIComponent(res.headers()['location'] ?? '').replace(/\+/g, ' ')).toMatch(/Payment failed/);
    await expectDashboardStays(page, '$0.00', 0);
    await expectDashboardStays(recipient.page, '$40.00', 1);
    await expectNoAuditRecord(request, eventId);
    await recipient.context.close();
  });

  test('replaying a submitted payment (same transaction UUID) is rejected: the sender is debited once and audited once', async ({ browser, page, request }) => {
    const recipient = await customerIn(browser);
    await signUp(page);
    await deposit(page, '50.00');
    await expectDashboard(page, '$50.00', undefined, 1);
    const { eventId, form } = await pay(page, recipient.customer.accountNumber, '20.00');
    await expectAlert(page, /\bPayment successful\s*$/);

    // Same session, same form body: what a double-click, browser resubmit or network retry sends.
    const replay = await page.request.post('/payment', {
      data: form, headers: { 'content-type': 'application/x-www-form-urlencoded' }, maxRedirects: 0,
    });

    expect(replay.status()).toBe(302);
    expect(decodeURIComponent(replay.headers()['location'] ?? '').replace(/\+/g, ' ')).toMatch(/Payment failed: .*duplicate/i);
    await expectDashboardStays(page, '$30.00', 2);
    await expectDashboardStays(recipient.page, '$20.00', 1);
    expect(await auditRecords(request, eventId)).toHaveLength(1);
    await auditRecord(request, eventId);
    await recipient.context.close();
  });
});
