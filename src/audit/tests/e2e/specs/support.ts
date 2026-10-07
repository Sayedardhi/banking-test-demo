import { execFileSync } from 'node:child_process';
import { expect, type APIRequestContext, type Page } from '@playwright/test';

export const AUDIT_URL = process.env.AUDIT_URL ?? 'http://localhost:8090';
const AUDIT_TOKEN = process.env.AUDIT_TOKEN ?? 'local-demo-audit-token';
/** Compose project whose audit container the outage test stops and restarts. */
const PROJECT = process.env.E2E_COMPOSE_PROJECT ?? 'banking-demo';
/** Synthetic external account/routing number used as the deposit source. */
export const EXTERNAL = { account: '9099791234', routing: '808889588' };

export interface Customer { username: string; accountNumber: string }
export interface AuditRecord {
  eventId: string; action: string; outcome: string; amountCents: number;
  fromAccount: string; toAccount: string; recordedAt: string; [key: string]: unknown;
}

export const masked = (account: string) => '******' + account.slice(-4);

/** The alert begins with a Material icon ligature ("check_circle"), so match the message at the end. */
export const expectAlert = (page: Page, message: RegExp) => expect(page.locator('#alert-message')).toHaveText(message);
export const expectBalance = (page: Page, amount: string) => expect(page.locator('#current-balance')).toHaveText(amount);

export async function signUp(page: Page): Promise<Customer> {
  const suffix = `${Date.now().toString(36).slice(-7)}${Math.random().toString(36).slice(2, 6)}`;
  const username = `au${suffix}`; // frontend allows 2-15 [A-Za-z0-9_]
  await page.goto('/signup');
  await page.locator('#signup-username').fill(username);
  await page.locator('#signup-password').fill('Audit-e2e-synthetic-1');
  await page.locator('#signup-password-repeat').fill('Audit-e2e-synthetic-1');
  await page.locator('#signup-firstname').fill('Synthetic');
  await page.locator('#signup-lastname').fill(`Auditee${suffix}`);
  await page.locator('#signup-birthday').fill('1985-06-15');
  await page.getByRole('button', { name: 'Create Account' }).click();
  await expect(page).toHaveURL(/\/home/);
  const accountNumber = (await page.locator('.balance-bottom strong').innerText()).trim();
  expect(accountNumber).toMatch(/^\d{10}$/);
  await expectBalance(page, '$0.00');
  return { username, accountNumber };
}

/** Submits a form and returns the transaction UUID the browser generated (the audit event ID). */
async function submit(page: Page, path: string, click: () => Promise<void>): Promise<string> {
  const [request] = await Promise.all([
    page.waitForRequest(r => r.method() === 'POST' && new URL(r.url()).pathname === path),
    click(),
  ]);
  const eventId = new URLSearchParams(request.postData() ?? '').get('uuid');
  expect(eventId).toMatch(/^[0-9a-f-]{36}$/i);
  await page.waitForURL(/\/home/);
  return eventId!;
}

export async function deposit(page: Page, amount: string): Promise<string> {
  await page.getByRole('button', { name: /Deposit Funds/ }).click();
  const form = page.locator('#deposit-form');
  await expect(form).toBeVisible();
  await form.locator('#accounts').selectOption('add');
  await form.locator('#external_account_num').fill(EXTERNAL.account);
  await form.locator('#external_routing_num').fill(EXTERNAL.routing);
  await form.locator('#deposit-amount').fill(amount);
  return submit(page, '/deposit', () => form.getByRole('button', { name: 'Deposit', exact: true }).click());
}

export async function pay(page: Page, recipient: string, amount: string): Promise<string> {
  await page.getByRole('button', { name: /Send Payment/ }).click();
  const form = page.locator('#payment-form');
  await expect(form).toBeVisible();
  await form.locator('#payment-accounts').selectOption('add');
  await form.locator('#contact_account_num').fill(recipient);
  await form.locator('#payment-amount').fill(amount);
  return submit(page, '/payment', () => form.getByRole('button', { name: 'Send', exact: true }).click());
}

export async function auditEvents(request: APIRequestContext): Promise<AuditRecord[]> {
  const res = await request.get(`${AUDIT_URL}/events`, { headers: { Authorization: `Bearer ${AUDIT_TOKEN}` } });
  expect(res.status()).toBe(200);
  return (await res.json()).events;
}

export async function auditRecordsFor(request: APIRequestContext, eventId: string) {
  return (await auditEvents(request)).filter(e => e.eventId === eventId.toLowerCase());
}

function auditContainer(): string {
  const id = execFileSync('docker', ['ps', '-aq', '--filter', `label=com.docker.compose.project=${PROJECT}`,
    '--filter', 'label=com.docker.compose.service=audit'], { encoding: 'utf8' }).trim();
  expect(id, `audit container of compose project ${PROJECT}`).toMatch(/^[0-9a-f]+$/);
  return id;
}

/** Controlled fault: stop the audit container (the frontend's audit dependency). */
export async function stopAudit(request: APIRequestContext) {
  execFileSync('docker', ['stop', '-t', '5', auditContainer()]);
  await expect.poll(async () => request.get(`${AUDIT_URL}/health`, { timeout: 2000 }).then(r => r.status(), () => 0)).not.toBe(200);
}

export async function startAudit(request: APIRequestContext) {
  execFileSync('docker', ['start', auditContainer()]);
  await expect.poll(async () => request.get(`${AUDIT_URL}/health`, { timeout: 2000 }).then(r => r.status(), () => 0),
    { timeout: 60_000 }).toBe(200);
}
