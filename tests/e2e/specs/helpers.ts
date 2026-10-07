import { expect, type APIRequestContext, type Page } from '@playwright/test';

export const AUDIT_URL = process.env.AUDIT_URL ?? 'http://localhost:8090';
const AUDIT_TOKEN = process.env.AUDIT_TOKEN ?? 'local-demo-audit-token';
const PASSWORD = 'E2e-synthetic-1';
export const EXTERNAL = { account: '9099791699', routing: '808889588' };

export interface Customer { username: string; password: string; accountNumber: string }
export interface AuditRecord {
  eventId: string; action: string; outcome: string; amountCents: number;
  fromAccount: string; toAccount: string; recordedAt: string; [key: string]: unknown;
}

export const masked = (account: string) => '******' + account.slice(-4);

/** Registers a brand-new synthetic customer so every test starts from a $0 balance and empty history. */
export async function signUp(page: Page): Promise<Customer> {
  const username = `pw${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
  await page.goto('/signup');
  await page.locator('#signup-username').fill(username);
  await page.locator('#signup-password').fill(PASSWORD);
  await page.locator('#signup-password-repeat').fill(PASSWORD);
  await page.locator('#signup-firstname').fill('Synthetic');
  await page.locator('#signup-lastname').fill('Customer');
  await page.locator('#signup-birthday').fill('1990-01-01');
  await page.getByRole('button', { name: 'Create Account' }).click();
  await expect(page).toHaveURL(/\/home/);
  const accountNumber = (await page.locator('.balance-bottom strong').innerText()).trim();
  expect(accountNumber).toMatch(/^\d{10}$/);
  await expect(page.locator('#current-balance')).toHaveText('$0.00');
  return { username, password: PASSWORD, accountNumber };
}

async function submitAndCaptureUuid(page: Page, path: string, submit: () => Promise<void>): Promise<string> {
  const [request] = await Promise.all([
    page.waitForRequest(r => r.method() === 'POST' && new URL(r.url()).pathname === path),
    submit(),
  ]);
  const uuid = new URLSearchParams(request.postData() ?? '').get('uuid');
  expect(uuid).toMatch(/^[0-9a-f-]{36}$/i);
  await page.waitForURL(/\/home/);
  return uuid!;
}

export async function deposit(page: Page, dollars: string): Promise<string> {
  await page.locator('#depositSpan').click();
  const dialog = page.getByRole('dialog', { name: 'Make a Deposit' });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('External Account').selectOption('add');
  await dialog.locator('#external_account_num').fill(EXTERNAL.account);
  await dialog.locator('#external_routing_num').fill(EXTERNAL.routing);
  await dialog.getByLabel('Deposit Amount').fill(dollars);
  return submitAndCaptureUuid(page, '/deposit', () => dialog.getByRole('button', { name: 'Deposit', exact: true }).click());
}

export async function pay(page: Page, recipient: string, dollars: string, opts = { bypassClientMax: false }): Promise<string> {
  await page.locator('#paymentSpan').click();
  const dialog = page.getByRole('dialog', { name: 'Send a Payment' });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('Recipient').selectOption('add');
  await dialog.locator('#contact_account_num').fill(recipient);
  const amount = dialog.getByLabel('Transaction Amount');
  if (opts.bypassClientMax) await amount.evaluate(el => el.removeAttribute('max'));
  await amount.fill(dollars);
  return submitAndCaptureUuid(page, '/payment', () => dialog.getByRole('button', { name: 'Send', exact: true }).click());
}

/** Reloads until the dashboard reflects backend state (balance/history are served by separate read services). */
export async function expectDashboard(page: Page, balance: string, firstRow?: RegExp[], rows?: number) {
  await expect(async () => {
    await page.reload();
    await expect(page.locator('#current-balance')).toHaveText(balance, { timeout: 2_000 });
    const history = page.locator('#transaction-list tr');
    if (rows !== undefined) await expect(history).toHaveCount(rows, { timeout: 2_000 });
    for (const pattern of firstRow ?? []) await expect(history.first()).toContainText(pattern, { timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

async function auditEvents(request: APIRequestContext): Promise<AuditRecord[]> {
  const res = await request.get(`${AUDIT_URL}/events`, { headers: { Authorization: `Bearer ${AUDIT_TOKEN}` } });
  expect(res.status()).toBe(200);
  return (await res.json()).events;
}

export async function auditRecord(request: APIRequestContext, eventId: string): Promise<AuditRecord> {
  let record: AuditRecord | undefined;
  await expect.poll(async () => (record = (await auditEvents(request)).find(e => e.eventId === eventId)) !== undefined,
    { message: `audit record for ${eventId}` }).toBe(true);
  return record!;
}

export async function expectNoAuditRecord(request: APIRequestContext, eventId: string) {
  expect((await auditEvents(request)).some(e => e.eventId === eventId)).toBe(false);
}
