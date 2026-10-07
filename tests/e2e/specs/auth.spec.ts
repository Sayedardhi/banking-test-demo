import { expect, test } from '@playwright/test';
import { expectDashboardStays, EXTERNAL, logIn, signUp } from './helpers';

const hasSession = async (context: { cookies(): Promise<{ name: string }[]> }) =>
  (await context.cookies()).some(c => c.name === 'token');

test.describe('authentication', () => {
  test('wrong password is rejected without a session; correct password opens that customer\'s dashboard', async ({ browser, page }) => {
    const customer = await signUp(page);

    const fresh = await browser.newContext();
    const login = await fresh.newPage();
    await logIn(login, customer.username, 'wrong-password');
    await expect(login).toHaveURL(/\/login/);
    await expect(login.getByText('Login Failed')).toBeVisible();
    expect(await hasSession(fresh)).toBe(false);
    await login.goto('/home');
    await expect(login).toHaveURL(/\/login/);

    await logIn(login, customer.username, customer.password);
    await expect(login).toHaveURL(/\/home/);
    expect(await hasSession(fresh)).toBe(true);
    await expect(login.locator('.balance-bottom strong')).toHaveText(customer.accountNumber);
    await expect(login.locator('#accountDropdown')).toContainText(`${customer.firstName} ${customer.lastName}`);
    await fresh.close();
  });

  test('sign out ends the session: token is removed and the dashboard is no longer reachable', async ({ page, context }) => {
    await signUp(page);
    expect(await hasSession(context)).toBe(true);

    await page.locator('#accountDropdown').click();
    await page.getByRole('button', { name: 'Sign out' }).click();

    await expect(page).toHaveURL(/\/login/);
    expect(await hasSession(context)).toBe(false);
    await page.goto('/home');
    await expect(page).toHaveURL(/\/login/);
  });

  test('a deposit posted without a session is refused with 401 and moves no money', async ({ page, playwright, baseURL }) => {
    const customer = await signUp(page);
    const anonymous = await playwright.request.newContext({ baseURL });

    const res = await anonymous.post('/deposit', {
      form: { account: 'add', external_account_num: EXTERNAL.account, external_routing_num: EXTERNAL.routing,
              amount: '50.00', uuid: crypto.randomUUID() },
      maxRedirects: 0,
    });

    expect(res.status()).toBe(401);
    await expectDashboardStays(page, '$0.00', 0);
    void customer;
    await anonymous.dispose();
  });
});
