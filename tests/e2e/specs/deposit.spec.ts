import { expect, test } from '@playwright/test';
import { LOCAL_ROUTING, auditRecordFor, auditRecords, balance, externalAccount, mask, signUp } from '../support/bank';

test.describe('deposit', () => {
  test('external deposit credits balance, history and a masked audit record', async ({ context }) => {
    const customer = await signUp(context);
    const page = await context.newPage();
    await page.goto('/home');
    expect(await balance(page)).toBe(0);
    const source = externalAccount();

    await page.locator('[data-target="#depositFunds"]').click();
    await expect(page.locator('#depositFunds')).toBeVisible();
    await page.locator('#accounts').selectOption('add');
    await page.locator('#external_account_num').fill(source);
    await page.locator('#external_routing_num').fill('808889588');
    await page.locator('#external_label').fill('Payroll');
    await page.locator('#deposit-amount').fill('125.50');
    const eventId = await page.locator('#deposit-uuid').inputValue();
    await page.locator('#deposit-form').getByRole('button', { name: 'Deposit' }).click();

    await expect(page.locator('#alert-message')).toHaveText(/\bDeposit successful\s*$/);
    await expect(page.locator('#current-balance')).toHaveText('$125.50');
    const row = page.locator('#transaction-list tr').first();
    await expect(row).toContainText('Credit');
    await expect(row).toContainText(source);
    await expect(row).toContainText('Payroll');
    await expect(row).toContainText('+$125.50');

    const record = await auditRecordFor(eventId);
    expect(record).toMatchObject({ eventId, action: 'deposit', outcome: 'succeeded', amountCents: 12550,
      fromAccount: mask(source), toAccount: mask(customer.account) });
    const raw = JSON.stringify(record);
    for (const sensitive of [source, customer.account, customer.username, customer.password]) {
      expect(raw).not.toContain(sensitive);
    }
  });

  test('deposit from a local routing number is refused with no ledger or audit effect', async ({ context }) => {
    await signUp(context);
    const page = await context.newPage();
    await page.goto('/home');
    await page.locator('[data-target="#depositFunds"]').click();
    await page.locator('#accounts').selectOption('add');
    await page.locator('#external_account_num').fill('1011226111');
    await page.locator('#external_routing_num').fill(LOCAL_ROUTING);
    await page.locator('#deposit-amount').fill('500');
    const eventId = await page.locator('#deposit-uuid').inputValue();
    await page.locator('#deposit-form').getByRole('button', { name: 'Deposit' }).click();

    await expect(page.locator('#alert-message')).toHaveText(/\bDeposit failed: invalid routing number\s*$/);
    await expect(page.locator('#current-balance')).toHaveText('$0.00');
    await expect(page.locator('#transaction-table')).toContainText('No Transactions Found');
    expect(await auditRecords(eventId)).toEqual([]);
  });

  test('a zero amount is blocked in the browser before submission', async ({ context }) => {
    await signUp(context);
    const page = await context.newPage();
    await page.goto('/home');
    await page.locator('[data-target="#depositFunds"]').click();
    await page.locator('#accounts').selectOption('add');
    await page.locator('#external_account_num').fill(externalAccount());
    await page.locator('#external_routing_num').fill('808889588');
    await page.locator('#deposit-amount').fill('0');
    const requests: string[] = [];
    page.on('request', r => { if (r.method() === 'POST') requests.push(r.url()); });
    await page.locator('#deposit-form').getByRole('button', { name: 'Deposit' }).click();
    await expect(page.locator('#deposit-form')).toHaveClass(/was-validated/);
    await expect(page.locator('#depositFunds')).toBeVisible();
    expect(requests).toEqual([]);
    await page.reload();
    expect(await balance(page)).toBe(0);
  });
});
