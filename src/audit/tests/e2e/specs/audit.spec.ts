import { expect, test } from '@playwright/test';
import { AUDIT_URL, EXTERNAL, auditRecordsFor, deposit, expectAlert, expectBalance, masked, pay, signUp,
  startAudit, stopAudit } from './support';

test.describe('audit trail for confirmed customer transactions', () => {
  test('a deposit and a payment each produce one minimized audit record keyed by the browser transaction UUID', async ({ browser, page, request }) => {
    const recipientContext = await browser.newContext();
    const recipientPage = await recipientContext.newPage();
    const recipient = await signUp(recipientPage);
    const sender = await signUp(page);

    const windowStart = Date.now();
    const depositId = await deposit(page, '60.00');
    await expectAlert(page, /\bDeposit successful\s*$/);
    await expectBalance(page, '$60.00');

    const paymentId = await pay(page, recipient.accountNumber, '12.34');
    await expectAlert(page, /\bPayment successful\s*$/);
    await expectBalance(page, '$47.66');
    await recipientPage.reload();
    await expectBalance(recipientPage, '$12.34');
    const windowEnd = Date.now();

    // The frontend posts the audit event before redirecting, so the record exists once the page is back.
    const [depositRecord] = await auditRecordsFor(request, depositId);
    const [paymentRecord] = await auditRecordsFor(request, paymentId);
    expect(await auditRecordsFor(request, depositId)).toHaveLength(1);
    expect(await auditRecordsFor(request, paymentId)).toHaveLength(1);
    expect(depositRecord).toEqual({
      eventId: depositId.toLowerCase(), action: 'deposit', outcome: 'succeeded', amountCents: 6000,
      fromAccount: masked(EXTERNAL.account), toAccount: masked(sender.accountNumber), recordedAt: expect.any(String),
    });
    expect(paymentRecord).toEqual({
      eventId: paymentId.toLowerCase(), action: 'payment', outcome: 'succeeded', amountCents: 1234,
      fromAccount: masked(sender.accountNumber), toAccount: masked(recipient.accountNumber), recordedAt: expect.any(String),
    });
    for (const record of [depositRecord, paymentRecord]) {
      expect(record.recordedAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
      const at = Date.parse(record.recordedAt);
      expect(at).toBeGreaterThanOrEqual(windowStart - 5_000);
      expect(at).toBeLessThanOrEqual(windowEnd + 5_000);
      const text = JSON.stringify(record);
      for (const raw of [sender.accountNumber, recipient.accountNumber, EXTERNAL.account, sender.username, recipient.username]) {
        expect(text, `audit record must not contain ${raw}`).not.toContain(raw);
      }
    }
    await recipientContext.close();
  });

  test('audit outage: the confirmed payment and deposit stay successful with an explicit warning and are not retried', async ({ browser, page, request }) => {
    const recipientContext = await browser.newContext();
    const recipientPage = await recipientContext.newPage();
    const recipient = await signUp(recipientPage);
    await signUp(page);
    await deposit(page, '30.00');
    await expectAlert(page, /\bDeposit successful\s*$/);

    await stopAudit(request);
    let paymentId = '';
    let outageDepositId = '';
    try {
      paymentId = await pay(page, recipient.accountNumber, '10.00');
      await expectAlert(page, /\bPayment successful; audit recording unavailable\s*$/);
      await expectBalance(page, '$20.00');
      outageDepositId = await deposit(page, '5.00');
      await expectAlert(page, /\bDeposit successful; audit recording unavailable\s*$/);
      await expectBalance(page, '$25.00');
    } finally {
      await startAudit(request);
    }

    // Money moved exactly once; the frontend did not resubmit the payment to retry auditing.
    await page.reload();
    await expectBalance(page, '$25.00');
    await recipientPage.reload();
    await expectBalance(recipientPage, '$10.00');
    await expect(recipientPage.locator('#transaction-table tbody tr')).toHaveCount(1);
    // No outbox exists (documented gap), so the outage leaves no record even after recovery.
    expect(await auditRecordsFor(request, paymentId)).toEqual([]);
    expect(await auditRecordsFor(request, outageDepositId)).toEqual([]);

    // Once audit is back, the next transaction is audited normally without a warning.
    const recoveredId = await deposit(page, '1.00');
    await expectAlert(page, /\bDeposit successful\s*$/);
    expect(await auditRecordsFor(request, recoveredId)).toHaveLength(1);
    await recipientContext.close();
  });

  test('the audit API refuses reads without the bearer token', async ({ request }) => {
    expect((await request.get(`${AUDIT_URL}/events`)).status()).toBe(401);
    expect((await request.get(`${AUDIT_URL}/events`, { headers: { Authorization: 'Bearer local-demo-audit-tokenx' } })).status()).toBe(401);
    const health = await request.get(`${AUDIT_URL}/health`);
    expect(health.status()).toBe(200);
    expect(await health.json()).toEqual({ status: 'ok' });
  });
});
