import { Browser, Page, expect } from '@playwright/test';
import { randomBytes, randomInt } from 'node:crypto';

export const LOCAL_ROUTING = '883745000';

export interface Customer {
  username: string;
  password: string;
  account: string;
}

export function syntheticAccountNumber(): string {
  return String(randomInt(1_000_000_000, 9_999_999_999));
}

function accountFromSession(token: string): string {
  const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
  return payload.acct;
}

/** Registers a brand-new customer through the signup UI so each test owns its data. */
export async function signUp(page: Page): Promise<Customer> {
  const username = `e2e${randomBytes(5).toString('hex')}`;
  const password = `pw-${randomBytes(6).toString('hex')}`;
  await page.goto('/signup');
  await page.fill('#signup-username', username);
  await page.fill('#signup-password', password);
  await page.fill('#signup-password-repeat', password);
  await page.fill('#signup-firstname', 'E2E');
  await page.fill('#signup-lastname', 'Customer');
  await page.fill('#signup-birthday', '1990-01-01');
  await page.getByRole('button', { name: 'Create Account' }).click();
  await expect(page).toHaveURL(/\/home/);
  const token = (await page.context().cookies()).find((c) => c.name === 'token');
  if (!token) throw new Error('no session token after signup');
  return { username, password, account: accountFromSession(token.value) };
}

export async function logIn(browser: Browser, customer: Customer): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await page.goto('/login');
  await page.fill('#login-username', customer.username);
  await page.fill('#login-password', customer.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/home/);
  return page;
}

export async function depositFromNewExternalAccount(page: Page, externalAccount: string,
  routing: string, amount: string): Promise<void> {
  await page.getByRole('button', { name: /Deposit Funds/ }).click();
  await page.selectOption('#accounts', 'add');
  await page.fill('#external_account_num', externalAccount);
  await page.fill('#external_routing_num', routing);
  await page.fill('#deposit-amount', amount);
  await page.locator('#deposit-form').getByRole('button', { name: 'Deposit' }).click();
  await expect(page.locator('#alert-message')).toHaveText(/Deposit successful\s*$/);
}

export async function openPaymentToNewRecipient(page: Page, recipient: string, amount: string): Promise<void> {
  await page.getByRole('button', { name: /Send Payment/ }).click();
  await page.selectOption('#payment-accounts', 'add');
  await page.fill('#contact_account_num', recipient);
  await page.fill('#payment-amount', amount);
}

export async function submitPayment(page: Page): Promise<void> {
  await page.locator('#payment-form').getByRole('button', { name: 'Send' }).click();
}

/**
 * Condition-based wait: balancereader and transactionhistory poll ledger-db,
 * so reload until the dashboard reflects the expected balance.
 */
export async function expectBalance(page: Page, expected: string): Promise<void> {
  await expect.poll(async () => {
    await page.goto('/home');
    return (await page.locator('#current-balance').innerText()).trim();
  }, { timeout: 30_000, intervals: [250, 500, 1000] }).toBe(expected);
}

export function historyRows(page: Page) {
  return page.locator('#transaction-list tr');
}
