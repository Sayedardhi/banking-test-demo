/**
 * Contacts journeys through the real frontend: saving payees/external accounts, reusing them,
 * and making sure a contact the contacts service rejects never moves money.
 * Every test signs up fresh synthetic customers in their own browser contexts.
 */
import { expect, test, type Browser, type Locator, type Page } from '@playwright/test';

const PASSWORD = 'Contacts-synthetic-1';
const EXTERNAL = { account: '9099791699', routing: '808889588' };
const LOCAL_ROUTING = '883745000';

async function signUp(page: Page): Promise<string> {
  const id = `ct${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  await page.goto('/signup');
  await page.locator('#signup-username').fill(id);
  await page.locator('#signup-password').fill(PASSWORD);
  await page.locator('#signup-password-repeat').fill(PASSWORD);
  await page.locator('#signup-firstname').fill('Synthetic');
  await page.locator('#signup-lastname').fill(`Contact ${id}`);
  await page.locator('#signup-birthday').fill('1991-02-03');
  await page.getByRole('button', { name: 'Create Account' }).click();
  await expect(page).toHaveURL(/\/home/);
  const account = (await page.locator('.balance-bottom strong').innerText()).trim();
  expect(account).toMatch(/^\d{10}$/);
  return account;
}

async function customer(browser: Browser) {
  const context = await browser.newContext();
  const page = await context.newPage();
  return { context, page, account: await signUp(page) };
}

const alert = (page: Page) => page.locator('#alert-message');

async function depositDialog(page: Page): Promise<Locator> {
  await page.locator('#depositSpan').click();
  const dialog = page.getByRole('dialog', { name: 'Make a Deposit' });
  await expect(dialog).toBeVisible();
  return dialog;
}

async function paymentDialog(page: Page): Promise<Locator> {
  await page.locator('#paymentSpan').click();
  const dialog = page.getByRole('dialog', { name: 'Send a Payment' });
  await expect(dialog).toBeVisible();
  return dialog;
}

async function depositFromNewAccount(page: Page, dollars: string, label: string, source = EXTERNAL) {
  const dialog = await depositDialog(page);
  await dialog.getByLabel('External Account').selectOption('add');
  await dialog.locator('#external_account_num').fill(source.account);
  await dialog.locator('#external_routing_num').fill(source.routing);
  await dialog.locator('#external_label').fill(label);
  await dialog.getByLabel('Deposit Amount').fill(dollars);
  await dialog.getByRole('button', { name: 'Deposit', exact: true }).click();
  await page.waitForURL(/\/home/);
}

async function expectBalance(page: Page, balance: string, rows: number) {
  await expect(async () => {
    await page.reload();
    await expect(page.locator('#current-balance')).toHaveText(balance, { timeout: 2_000 });
    await expect(page.locator('#transaction-list tr')).toHaveCount(rows, { timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

async function closeDialog(dialog: Locator) {
  await dialog.getByRole('button', { name: 'Close' }).click();
  await expect(dialog).toBeHidden();
}

/** Options in a select, excluding the separator and the "add new" entry. */
async function savedOptions(select: Locator) {
  return (await select.locator('option:not([disabled]):not([value="add"])').allInnerTexts()).map(t => t.trim());
}

test.describe('saved contacts', () => {
  test('labelled external account is saved, offered again, and labels the deposit in history', async ({ page }) => {
    await signUp(page);
    await depositFromNewAccount(page, '20.00', 'Credit Union');
    await expect(alert(page)).toHaveText(/Deposit successful\s*$/);
    await expectBalance(page, '$20.00', 1);
    await expect(page.locator('#transaction-list tr').first().locator('.transaction-label')).toHaveText('Credit Union');

    const dialog = await depositDialog(page);
    const select = dialog.getByLabel('External Account');
    expect(await savedOptions(select)).toEqual([`Credit Union - ${EXTERNAL.account} - ${EXTERNAL.routing}`]);
    // External accounts are deposit sources only; they are not offered as payment recipients.
    await closeDialog(dialog);
    const payment = await paymentDialog(page);
    expect(await savedOptions(payment.getByLabel('Recipient'))).toEqual([]);
    await closeDialog(payment);

    // Re-using the saved contact deposits from the stored account/routing numbers.
    const again = await depositDialog(page);
    await again.getByLabel('External Account').selectOption({ label: `Credit Union - ${EXTERNAL.account} - ${EXTERNAL.routing}` });
    await again.getByLabel('Deposit Amount').fill('5.00');
    await again.getByRole('button', { name: 'Deposit', exact: true }).click();
    await expect(alert(page)).toHaveText(/Deposit successful\s*$/);
    await expectBalance(page, '$25.00', 2);
  });

  test('labelled payee is saved as an internal recipient visible only to its owner', async ({ browser, page }) => {
    const payee = await customer(browser);
    await signUp(page);
    await depositFromNewAccount(page, '50.00', 'Funding');
    await expectBalance(page, '$50.00', 1);

    const dialog = await paymentDialog(page);
    await dialog.getByLabel('Recipient').selectOption('add');
    await dialog.locator('#contact_account_num').fill(payee.account);
    await dialog.locator('#contact_label').fill('Landlord');
    await dialog.getByLabel('Transaction Amount').fill('12.34');
    await dialog.getByRole('button', { name: 'Send', exact: true }).click();
    await expect(alert(page)).toHaveText(/Payment successful\s*$/);
    await expectBalance(page, '$37.66', 2);
    await expect(page.locator('#transaction-list tr').first().locator('.transaction-label')).toHaveText('Landlord');

    const payment = await paymentDialog(page);
    expect(await savedOptions(payment.getByLabel('Recipient'))).toEqual([`Landlord - ${payee.account}`]);
    await closeDialog(payment);
    expect(await savedOptions((await depositDialog(page)).getByLabel('External Account'))).toEqual(['Funding - 9099791699 - 808889588']);

    // The payee's own contact list is untouched by someone else saving them.
    expect(await savedOptions((await paymentDialog(payee.page)).getByLabel('Recipient'))).toEqual([]);
    await payee.context.close();
  });
});

test.describe('rejected contacts never move money', () => {
  test('duplicate label is rejected by the contacts service and the deposit is not made', async ({ page }) => {
    await signUp(page);
    await depositFromNewAccount(page, '10.00', 'Savings');
    await expectBalance(page, '$10.00', 1);

    await depositFromNewAccount(page, '99.00', 'Savings', { account: '9099791700', routing: EXTERNAL.routing });
    await expect(alert(page)).toHaveText(/Deposit failed: contact already exists with that label\s*$/);
    await expectBalance(page, '$10.00', 1);
    expect(await savedOptions((await depositDialog(page)).getByLabel('External Account')))
      .toEqual([`Savings - ${EXTERNAL.account} - ${EXTERNAL.routing}`]);
  });

  test('label that bypasses the browser pattern is rejected server-side and the payment is not sent', async ({ browser, page }) => {
    const payee = await customer(browser);
    await signUp(page);
    await depositFromNewAccount(page, '30.00', 'Funding');
    await expectBalance(page, '$30.00', 1);

    // index.html enforces the label pattern in the browser; post the form directly so the contacts service decides.
    await page.request.post('/payment', {
      form: { account_num: 'add', contact_account_num: payee.account, contact_label: '<img src=x onerror=alert(1)>',
              amount: '7.00', uuid: crypto.randomUUID() },
    });
    await expectBalance(page, '$30.00', 1);
    await expectBalance(payee.page, '$0.00', 0);
    expect(await savedOptions((await paymentDialog(page)).getByLabel('Recipient'))).toEqual([]);
    await payee.context.close();
  });

  test('external account claiming this bank\'s routing number cannot be saved', async ({ page }) => {
    await signUp(page);
    await depositFromNewAccount(page, '15.00', 'Spoofed', { account: '1011226111', routing: LOCAL_ROUTING });
    await expect(alert(page)).toHaveText(/Deposit failed: invalid routing number\s*$/);
    await expectBalance(page, '$0.00', 0);
    expect(await savedOptions((await depositDialog(page)).getByLabel('External Account'))).toEqual([]);
  });
});
