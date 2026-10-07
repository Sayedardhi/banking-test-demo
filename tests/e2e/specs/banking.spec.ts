import { expect, test } from '@playwright/test';
import { auditRecord, deposit, EXTERNAL, expectDashboard, expectNoAuditRecord, masked, pay, signUp } from './helpers';

test.describe('login', () => {
  test('wrong password is rejected without a session; correct password opens that customer\'s dashboard', async ({ browser, page }) => {
    const customer = await signUp(page);

    const fresh = await browser.newContext();
    const login = await fresh.newPage();
    await login.goto('/login');
    await login.locator('#login-username').fill(customer.username);
    await login.locator('#login-password').fill('wrong-password');
    await login.locator('#login-password').press('Enter');
    await expect(login).toHaveURL(/\/login/);
    await expect(login.getByText('Login Failed')).toBeVisible();
    expect((await fresh.cookies()).some(c => c.name === 'token')).toBe(false);
    await login.goto('/home');
    await expect(login).toHaveURL(/\/login/);

    await login.locator('#login-username').fill(customer.username);
    await login.locator('#login-password').fill(customer.password);
    await login.locator('#login-password').press('Enter');
    await expect(login).toHaveURL(/\/home/);
    await expect(login.locator('.balance-bottom strong')).toHaveText(customer.accountNumber);
    await fresh.close();
  });
});

test.describe('deposit', () => {
  test('credits balance and history, and writes a masked audit record with the browser UUID', async ({ page, request }) => {
    const customer = await signUp(page);

    const eventId = await deposit(page, '123.45');

    await expect(page.locator('#alert-message')).toHaveText(/\bDeposit successful\s*$/);
    await expectDashboard(page, '$123.45', [/Credit/, new RegExp(EXTERNAL.account), /\+\$123\.45/], 1);
    const record = await auditRecord(request, eventId);
    expect(record).toMatchObject({
      action: 'deposit', outcome: 'succeeded', amountCents: 12345,
      fromAccount: masked(EXTERNAL.account), toAccount: masked(customer.accountNumber),
    });
    expect(JSON.stringify(record)).not.toContain(customer.accountNumber);
    expect(JSON.stringify(record)).not.toContain(EXTERNAL.account);
  });
});

test.describe('payment', () => {
  test('debits the sender, credits the recipient, and writes a masked audit record', async ({ browser, page, request }) => {
    const recipientContext = await browser.newContext();
    const recipientPage = await recipientContext.newPage();
    const recipient = await signUp(recipientPage);
    const sender = await signUp(page);
    await deposit(page, '100.00');
    await expectDashboard(page, '$100.00');

    const eventId = await pay(page, recipient.accountNumber, '25.50');

    await expect(page.locator('#alert-message')).toHaveText(/\bPayment successful\s*$/);
    await expectDashboard(page, '$74.50', [/Debit/, new RegExp(recipient.accountNumber), /-\$25\.50/], 2);
    await expectDashboard(recipientPage, '$25.50', [/Credit/, new RegExp(sender.accountNumber), /\+\$25\.50/], 1);
    const record = await auditRecord(request, eventId);
    expect(record).toMatchObject({
      action: 'payment', outcome: 'succeeded', amountCents: 2550,
      fromAccount: masked(sender.accountNumber), toAccount: masked(recipient.accountNumber),
    });
    expect(JSON.stringify(record)).not.toContain(sender.accountNumber);
    await recipientContext.close();
  });

  test('overdraft that bypasses the browser limit is rejected by the server: no money moves and nothing is audited', async ({ browser, page, request }) => {
    const recipientContext = await browser.newContext();
    const recipient = await signUp(await recipientContext.newPage());
    await signUp(page);
    await deposit(page, '10.00');
    await expectDashboard(page, '$10.00', undefined, 1);

    const eventId = await pay(page, recipient.accountNumber, '10.01', { bypassClientMax: true });

    await expect(page.locator('#alert-message')).toContainText('Payment failed');
    await expectDashboard(page, '$10.00', [/Credit/], 1);
    await expectNoAuditRecord(request, eventId);
    const recipientPage = await recipientContext.newPage();
    await recipientPage.goto('/home');
    await expectDashboard(recipientPage, '$0.00', undefined, 0);
    await recipientContext.close();
  });
});
