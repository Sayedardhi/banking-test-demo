import { expect, type APIRequestContext, type BrowserContext, type Page, request } from '@playwright/test';

export const LOCAL_ROUTING = '883745000';
export const AUDIT_URL = process.env.AUDIT_URL ?? 'http://localhost:18090';
const AUDIT_TOKEN = process.env.AUDIT_TOKEN ?? 'local-demo-audit-token';

export interface Customer { username: string; password: string; account: string }
export interface AuditRecord {
  eventId: string; action: string; outcome: string; amountCents: number;
  fromAccount: string; toAccount: string; recordedAt: string;
}

export const mask = (account: string) => '******' + account.slice(-4);
export const cents = (text: string) => {
  const match = text.trim().match(/^(-?)\$([\d,]+)\.(\d{2})$/);
  if (!match) throw new Error(`not a currency amount: "${text}"`);
  return (match[1] ? -1 : 1) * (Number(match[2].replace(/,/g, '')) * 100 + Number(match[3]));
};

/** Synthetic external account numbers: ten digits, unique per call. */
export const externalAccount = () => String(9_000_000_000 + Math.floor(Math.random() * 999_999_999));

/** Registers a fresh synthetic customer through the real /signup endpoint; the context is logged in. */
export async function signUp(context: BrowserContext): Promise<Customer> {
  const username = ('e2e' + Date.now().toString(36) + Math.random().toString(36).slice(2, 5)).slice(0, 15);
  const password = 'E2e-' + Math.random().toString(36).slice(2, 10);
  const resp = await context.request.post('/signup', {
    form: { username, password, 'password-repeat': password, firstname: 'Synthetic',
            lastname: 'Customer', birthday: '1990-01-01', timezone: '-5', address: '1 Test Way',
            country: 'United States', state: 'NY', zip: '10004', ssn: '000-00-0000' },
  });
  expect(resp.ok(), `signup failed: ${resp.status()}`).toBeTruthy();
  const page = await context.newPage();
  await page.goto('/home');
  const account = (await page.locator('.balance-bottom strong').innerText()).trim();
  expect(account).toMatch(/^\d{10}$/);
  await page.close();
  return { username, password, account };
}

/** Test setup only: funds a customer through the frontend's /deposit endpoint. */
export async function fund(context: BrowserContext, amount: string) {
  const resp = await context.request.post('/deposit', {
    form: { account: 'add', external_account_num: externalAccount(), external_routing_num: '808889588',
            external_label: '', amount, uuid: crypto.randomUUID() },
    maxRedirects: 0,
  });
  expect(resp.status()).toBe(303);
  expect(new URL(resp.headers()['location']).searchParams.get('msg')).toBe('Deposit successful');
}

export async function login(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.locator('#login-username').fill(username);
  await page.locator('#login-password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

export async function balance(page: Page) {
  return cents(await page.locator('#current-balance').innerText());
}

let auditApi: APIRequestContext | undefined;
async function audit() {
  auditApi ??= await request.newContext({ baseURL: AUDIT_URL,
    extraHTTPHeaders: { Authorization: `Bearer ${AUDIT_TOKEN}` } });
  return auditApi;
}

export async function auditRecords(eventId: string): Promise<AuditRecord[]> {
  const resp = await (await audit()).get('/events');
  expect(resp.status()).toBe(200);
  const body = await resp.json() as { events: AuditRecord[] };
  return body.events.filter(e => e.eventId === eventId);
}

/** Polls the authenticated audit API until exactly one record for the event exists. */
export async function auditRecordFor(eventId: string): Promise<AuditRecord> {
  await expect.poll(async () => (await auditRecords(eventId)).length,
    { message: `audit record for ${eventId}` }).toBe(1);
  return (await auditRecords(eventId))[0];
}
