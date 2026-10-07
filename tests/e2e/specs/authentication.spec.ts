import { expect, test } from '@playwright/test';
import { login } from '../support/bank';

const DEMO_USER = 'testuser';
const DEMO_PASSWORD = 'bankofanthos';

test.describe('authentication', () => {
  test('demo customer signs in, sees the account overview, and signs out', async ({ page, context }) => {
    await login(page, DEMO_USER, DEMO_PASSWORD);
    await expect(page).toHaveURL(/\/home$/);
    await expect(page.locator('#current-balance')).toHaveText(/^\$[\d,]+\.\d{2}$/);
    await expect(page.locator('#transaction-table')).toBeVisible();
    expect((await context.cookies()).some(c => c.name === 'token')).toBe(true);

    await page.locator('#accountDropdown').click();
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect((await context.cookies()).find(c => c.name === 'token')).toBeUndefined();
    await page.goto('/home');
    await expect(page).toHaveURL(/\/login$/);
  });

  test('wrong password is refused without issuing a session', async ({ page, context }) => {
    await login(page, DEMO_USER, 'not-the-password');
    await expect(page).toHaveURL(/\/login\?msg=Login\+Failed$/);
    await expect(page.locator('#login-form')).toBeVisible();
    await expect(page.getByText('Login Failed')).toBeVisible();
    expect((await context.cookies()).find(c => c.name === 'token')).toBeUndefined();
  });

  test('anonymous visitor is sent to login and cannot transact', async ({ page }) => {
    await page.goto('/home');
    await expect(page).toHaveURL(/\/login$/);
    const pay = await page.request.post('/payment', { maxRedirects: 0,
      form: { account_num: '1033623433', amount: '1.00', uuid: crypto.randomUUID() } });
    expect(pay.status()).toBe(401);
  });

  test('a forged session cookie is rejected', async ({ page, context, baseURL }) => {
    const header = Buffer.from('{"alg":"none","typ":"JWT"}').toString('base64url');
    const claims = Buffer.from(JSON.stringify({ user: DEMO_USER, acct: '1011226111', name: 'Forged',
      iat: Math.floor(Date.now() / 1000), exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url');
    await context.addCookies([{ name: 'token', value: `${header}.${claims}.`, url: baseURL! }]);
    await page.goto('/home');
    await expect(page).toHaveURL(/\/login$/);
    const deposit = await page.request.post('/deposit', { maxRedirects: 0,
      form: { account: 'add', external_account_num: '9099791699', external_routing_num: '808889588',
              amount: '1', uuid: crypto.randomUUID() } });
    expect(deposit.status()).toBe(401);
  });

  test('a new customer can register and lands on an empty account', async ({ page, context }) => {
    const username = ('e2esu' + Date.now().toString(36)).slice(0, 15);
    await page.goto('/signup');
    await page.locator('#signup-username').fill(username);
    await page.locator('#signup-password').fill('Signup-Pass1');
    await page.locator('#signup-password-repeat').fill('Signup-Pass1');
    await page.locator('#signup-firstname').fill('Synthetic');
    await page.locator('#signup-lastname').fill('Signup');
    await page.locator('#signup-birthday').fill('1991-02-03');
    await page.getByRole('button', { name: 'Create Account' }).click();
    await expect(page).toHaveURL(/\/home$/);
    await expect(page.locator('#current-balance')).toHaveText('$0.00');
    await expect(page.locator('#transaction-table')).toContainText('No Transactions Found');
    expect((await context.cookies()).some(c => c.name === 'token')).toBe(true);
  });
});
