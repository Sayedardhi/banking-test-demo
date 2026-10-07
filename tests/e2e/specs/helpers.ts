import { expect, type APIRequestContext, type Page } from '@playwright/test';

export const AUDIT_URL = process.env.AUDIT_URL ?? 'http://localhost:8090';
const AUDIT_TOKEN = process.env.AUDIT_TOKEN ?? 'local-demo-audit-token';
const PASSWORD = 'E2e-synthetic-1';
/** Synthetic external bank account used as the deposit source (not a local customer). */
export const EXTERNAL = { account: '9099791699', routing: '808889588' };
/** Routing number of this bank; deposits must never claim to come from it. */
export const LOCAL_ROUTING = '883745000';

export interface Customer { username: string; password: string; accountNumber: string; firstName: string; lastName: string }
export interface Submission { eventId: string; form: string }
export interface AuditRecord {
  eventId: string; action: string; outcome: string; amountCents: number;
  fromAccount: string; toAccount: string; [key: string]: unknown;
}

export const masked = (account: string) => '******' + account.slice(-4);
const alert = (page: Page) => page.locator('#alert-message');
/** The alert renders a Material icon whose ligature text ("check_circle"/"error") precedes the message. */
export const expectAlert = (page: Page, message: RegExp) => expect(alert(page)).toHaveText(message);

/** Registers a brand-new synthetic customer so every test starts from a $0 balance and empty history. */
export async function signUp(page: Page): Promise<Customer> {
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
  const customer = { username: `pw${suffix}`, password: PASSWORD, firstName: 'Synthetic', lastName: `Customer${suffix}`, accountNumber: '' };
  await page.goto('/signup');
  await page.locator('#signup-username').fill(customer.username);
  await page.locator('#signup-password').fill(PASSWORD);
  await page.locator('#signup-password-repeat').fill(PASSWORD);
  await page.locator('#signup-firstname').fill(customer.firstName);
  await page.locator('#signup-lastname').fill(customer.lastName);
  await page.locator('#signup-birthday').fill('1990-01-01');
  await page.getByRole('button', { name: 'Create Account' }).click();
  await expect(page).toHaveURL(/\/home/);
  customer.accountNumber = (await page.locator('.balance-bottom strong').innerText()).trim();
  expect(customer.accountNumber).toMatch(/^\d{10}$/);
  await expect(page.locator('#current-balance')).toHaveText('$0.00');
  return customer;
}

export async function logIn(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.locator('#login-username').fill(username);
  await page.locator('#login-password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function submitAndCapture(page: Page, path: string, submit: () => Promise<void>): Promise<Submission> {
  const [request] = await Promise.all([
    page.waitForRequest(r => r.method() === 'POST' && new URL(r.url()).pathname === path),
    submit(),
  ]);
  const form = request.postData() ?? '';
  const eventId = new URLSearchParams(form).get('uuid');
  expect(eventId).toMatch(/^[0-9a-f-]{36}$/i);
  await page.waitForURL(/\/home/);
  return { eventId: eventId!, form };
}

export async function deposit(page: Page, dollars: string, source = EXTERNAL): Promise<Submission> {
  await page.locator('#depositSpan').click();
  const dialog = page.getByRole('dialog', { name: 'Make a Deposit' });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('External Account').selectOption('add');
  await dialog.locator('#external_account_num').fill(source.account);
  await dialog.locator('#external_routing_num').fill(source.routing);
  await dialog.getByLabel('Deposit Amount').fill(dollars);
  return submitAndCapture(page, '/deposit', () => dialog.getByRole('button', { name: 'Deposit', exact: true }).click());
}

/** Sends a payment. `bypassClientLimits` removes the input's min/max so the server-side rules are what is tested. */
export async function pay(page: Page, recipient: string, dollars: string, opts = { bypassClientLimits: false }): Promise<Submission> {
  await page.locator('#paymentSpan').click();
  const dialog = page.getByRole('dialog', { name: 'Send a Payment' });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('Recipient').selectOption('add');
  await dialog.locator('#contact_account_num').fill(recipient);
  const amount = dialog.getByLabel('Transaction Amount');
  if (opts.bypassClientLimits) await amount.evaluate(el => { el.removeAttribute('max'); el.removeAttribute('min'); });
  await amount.fill(dollars);
  return submitAndCapture(page, '/payment', () => dialog.getByRole('button', { name: 'Send', exact: true }).click());
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

/** Checks a balance/history is still unchanged after read services have had time to catch up. */
export async function expectDashboardStays(page: Page, balance: string, rows: number) {
  await expectDashboard(page, balance, undefined, rows);
  await page.waitForTimeout(1_500); // > 10x the read services' 100 ms ledger poll interval
  await expectDashboard(page, balance, undefined, rows);
}

async function auditEvents(request: APIRequestContext): Promise<AuditRecord[]> {
  const res = await request.get(`${AUDIT_URL}/events`, { headers: { Authorization: `Bearer ${AUDIT_TOKEN}` } });
  expect(res.status()).toBe(200);
  return (await res.json()).events;
}

export async function auditRecords(request: APIRequestContext, eventId: string): Promise<AuditRecord[]> {
  return (await auditEvents(request)).filter(e => e.eventId === eventId.toLowerCase());
}

export async function auditRecord(request: APIRequestContext, eventId: string): Promise<AuditRecord> {
  await expect.poll(async () => (await auditRecords(request, eventId)).length, { message: `audit record for ${eventId}` }).toBe(1);
  return (await auditRecords(request, eventId))[0];
}

/** The frontend writes the audit event synchronously before redirecting, so absence after the redirect is final. */
export async function expectNoAuditRecord(request: APIRequestContext, eventId: string) {
  expect(await auditRecords(request, eventId)).toEqual([]);
}

/** Asserts that a stored audit record contains no unmasked account numbers or customer identity fields. */
export function expectMinimized(record: AuditRecord, secrets: string[]) {
  const text = JSON.stringify(record);
  for (const secret of secrets) expect(text, `audit record must not contain "${secret}"`).not.toContain(secret);
}
