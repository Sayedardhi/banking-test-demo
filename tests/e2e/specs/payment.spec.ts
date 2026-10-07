import { expect, test } from '@playwright/test';
import { auditRecordFor, auditRecords, balance, fund, mask, signUp } from '../support/bank';

test.describe('payment', () => {
  test('payment moves money between customers and is audited with masked accounts', async ({ browser }) => {
    const payerContext = await browser.newContext();
    const payeeContext = await browser.newContext();
    const payer = await signUp(payerContext);
    const payee = await signUp(payeeContext);
    await fund(payerContext, '200.00');

    const page = await payerContext.newPage();
    await page.goto('/home');
    expect(await balance(page)).toBe(20000);
    await page.locator('[data-target="#sendPayment"]').click();
    await expect(page.locator('#sendPayment')).toBeVisible();
    await page.locator('#payment-accounts').selectOption('add');
    await page.locator('#contact_account_num').fill(payee.account);
    await page.locator('#contact_label').fill('Landlord');
    await page.locator('#payment-amount').fill('40.25');
    const eventId = await page.locator('#payment-uuid').inputValue();
    await page.locator('#payment-form').getByRole('button', { name: 'Send' }).click();

    await expect(page.locator('#alert-message')).toHaveText(/\bPayment successful\s*$/);
    await expect(page.locator('#current-balance')).toHaveText('$159.75');
    const debit = page.locator('#transaction-list tr').first();
    await expect(debit).toContainText('Debit');
    await expect(debit).toContainText(payee.account);
    await expect(debit).toContainText('Landlord');
    await expect(debit).toContainText('-$40.25');
    await expect(page.locator('#payment-accounts')).toContainText(`Landlord - ${payee.account}`);

    const payeePage = await payeeContext.newPage();
    await payeePage.goto('/home');
    await expect(payeePage.locator('#current-balance')).toHaveText('$40.25');
    await expect(payeePage.locator('#transaction-list tr').first()).toContainText(payer.account);
    await expect(payeePage.locator('#transaction-list tr').first()).toContainText('+$40.25');

    const record = await auditRecordFor(eventId);
    expect(record).toMatchObject({ eventId, action: 'payment', outcome: 'succeeded', amountCents: 4025,
      fromAccount: mask(payer.account), toAccount: mask(payee.account) });
    expect(JSON.stringify(record)).not.toContain(payer.account);
    expect(JSON.stringify(record)).not.toContain(payee.account);
    await payerContext.close();
    await payeeContext.close();
  });

  test('payment above the available balance is blocked in the browser and refused by the bank', async ({ browser }) => {
    const payerContext = await browser.newContext();
    const payeeContext = await browser.newContext();
    await signUp(payerContext);
    const payee = await signUp(payeeContext);
    await fund(payerContext, '10.00');
    const page = await payerContext.newPage();
    await page.goto('/home');
    await page.locator('[data-target="#sendPayment"]').click();
    await page.locator('#payment-accounts').selectOption('add');
    await page.locator('#contact_account_num').fill(payee.account);
    await page.locator('#payment-amount').fill('10.01');
    const posts: string[] = [];
    page.on('request', r => { if (r.method() === 'POST') posts.push(r.url()); });
    await page.locator('#payment-form').getByRole('button', { name: 'Send' }).click();
    await expect(page.locator('#payment-form')).toHaveClass(/was-validated/);
    await expect(page.locator('#payment-amount')).toHaveJSProperty('validity.rangeOverflow', true);
    expect(posts).toEqual([]);

    // The browser limit is advisory: a crafted form post must still be refused server-side.
    const eventId = crypto.randomUUID();
    const crafted = await payerContext.request.post('/payment', { maxRedirects: 0,
      form: { account_num: payee.account, amount: '10.01', uuid: eventId } });
    expect(new URL(crafted.headers()['location']).searchParams.get('msg'))
      .toBe('Payment failed: insufficient balance');
    await page.goto('/home');
    await expect(page.locator('#current-balance')).toHaveText('$10.00');
    await expect(page.locator('#transaction-list tr')).toHaveCount(1);
    expect(await auditRecords(eventId)).toEqual([]);
    const payeePage = await payeeContext.newPage();
    await payeePage.goto('/home');
    await expect(payeePage.locator('#current-balance')).toHaveText('$0.00');
    await payerContext.close();
    await payeeContext.close();
  });

  test('a payment to oneself is refused and leaves the balance unchanged', async ({ context }) => {
    const customer = await signUp(context);
    await fund(context, '25.00');
    const page = await context.newPage();
    await page.goto('/home');
    await page.locator('[data-target="#sendPayment"]').click();
    await page.locator('#payment-accounts').selectOption('add');
    await page.locator('#contact_account_num').fill(customer.account);
    await page.locator('#payment-amount').fill('5.00');
    const eventId = await page.locator('#payment-uuid').inputValue();
    await page.locator('#payment-form').getByRole('button', { name: 'Send' }).click();

    await expect(page.locator('#alert-message')).toHaveText(/\bPayment failed\b/);
    await expect(page.locator('#current-balance')).toHaveText('$25.00');
    expect(await auditRecords(eventId)).toEqual([]);
  });

  test('a replayed payment form (same transaction id) is not applied twice', async ({ browser }) => {
    const payerContext = await browser.newContext();
    const payeeContext = await browser.newContext();
    await signUp(payerContext);
    const payee = await signUp(payeeContext);
    await fund(payerContext, '50.00');
    const eventId = crypto.randomUUID();
    const form = { account_num: payee.account, amount: '7.00', uuid: eventId };
    const first = await payerContext.request.post('/payment', { form, maxRedirects: 0 });
    const replay = await payerContext.request.post('/payment', { form, maxRedirects: 0 });
    expect(new URL(first.headers()['location']).searchParams.get('msg')).toBe('Payment successful');
    expect(new URL(replay.headers()['location']).searchParams.get('msg')).toMatch(/^Payment failed/);

    const page = await payerContext.newPage();
    await page.goto('/home');
    await expect(page.locator('#current-balance')).toHaveText('$43.00');
    expect(await auditRecords(eventId)).toHaveLength(1);
    await payerContext.close();
    await payeeContext.close();
  });
});
