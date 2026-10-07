import { expect, test } from '@playwright/test';
import {
  LOCAL_ROUTING, depositFromNewExternalAccount, expectBalance, historyRows, logIn,
  openPaymentToNewRecipient, signUp, submitPayment, syntheticAccountNumber,
} from '../support/bank';
import { ledgerRowsInvolving } from '../support/ledger';

// Every journey registers fresh customers and synthetic external accounts, so
// tests are isolated from the demo testuser and from each other. Nothing is
// stubbed: frontend, userservice, contacts, audit, balancereader,
// transactionhistory and ledger-db are the Compose services; ledgerwriter is
// built from this checkout (verified in global-setup).

const EXTERNAL_ROUTING = '121000358';

test('deposit from an external account credits the balance, history and ledger', async ({ page }) => {
  const customer = await signUp(page);
  const external = syntheticAccountNumber();
  await expectBalance(page, '$0.00');

  await depositFromNewExternalAccount(page, external, EXTERNAL_ROUTING, '250.75');

  await expectBalance(page, '$250.75');
  const latest = historyRows(page).first();
  await expect(latest.locator('.transaction-type')).toContainText('Credit');
  await expect(latest.locator('.transaction-account')).toHaveText(external);
  await expect(latest.locator('.transaction-amount')).toHaveText('+$250.75');
  expect(ledgerRowsInvolving(customer.account)).toEqual([
    { fromAcct: external, fromRoute: EXTERNAL_ROUTING, toAcct: customer.account, toRoute: LOCAL_ROUTING, amount: 25075 },
  ]);
});

test('payment between two customers debits the sender and credits the recipient', async ({ page, browser }) => {
  const sender = await signUp(page);
  const recipient = await signUp(await (await browser.newContext()).newPage());
  await depositFromNewExternalAccount(page, syntheticAccountNumber(), EXTERNAL_ROUTING, '200.00');
  await expectBalance(page, '$200.00');

  await openPaymentToNewRecipient(page, recipient.account, '75.25');
  await submitPayment(page);

  await expect(page.locator('#alert-message')).toHaveText(/Payment successful\s*$/);
  await expectBalance(page, '$124.75');
  const debit = historyRows(page).first();
  await expect(debit.locator('.transaction-type')).toContainText('Debit');
  await expect(debit.locator('.transaction-account')).toHaveText(recipient.account);
  await expect(debit.locator('.transaction-amount')).toHaveText('-$75.25');

  const recipientPage = await logIn(browser, recipient);
  await expectBalance(recipientPage, '$75.25');
  const credit = historyRows(recipientPage).first();
  await expect(credit.locator('.transaction-account')).toHaveText(sender.account);
  await expect(credit.locator('.transaction-amount')).toHaveText('+$75.25');

  expect(ledgerRowsInvolving(recipient.account)).toEqual([
    { fromAcct: sender.account, fromRoute: LOCAL_ROUTING, toAcct: recipient.account, toRoute: LOCAL_ROUTING, amount: 7525 },
  ]);
  expect(ledgerRowsInvolving(sender.account)).toHaveLength(2);
});

test('overdraft is refused by ledgerwriter even when the browser check is bypassed', async ({ page }) => {
  const customer = await signUp(page);
  await depositFromNewExternalAccount(page, syntheticAccountNumber(), EXTERNAL_ROUTING, '10.00');
  await expectBalance(page, '$10.00');
  const recipient = syntheticAccountNumber();

  // The form caps the amount at the balance; drop that client-side limit so
  // the request reaches ledgerwriter, as a stale or tampered client would.
  await openPaymentToNewRecipient(page, recipient, '50.00');
  await page.locator('#payment-amount').evaluate((el) => el.removeAttribute('max'));
  await submitPayment(page);

  await expect(page.locator('#alert-message')).toHaveText(/Payment failed: insufficient balance\s*$/);
  await expectBalance(page, '$10.00');
  await expect(historyRows(page)).toHaveCount(1);
  expect(ledgerRowsInvolving(recipient)).toEqual([]);
  expect(ledgerRowsInvolving(customer.account)).toHaveLength(1);
});

test('replaying a submitted payment form does not debit twice', async ({ page }) => {
  const customer = await signUp(page);
  await depositFromNewExternalAccount(page, syntheticAccountNumber(), EXTERNAL_ROUTING, '100.00');
  await expectBalance(page, '$100.00');
  const recipient = syntheticAccountNumber();

  await openPaymentToNewRecipient(page, recipient, '30.00');
  const uuid = await page.locator('#payment-uuid').inputValue();
  expect(uuid).toMatch(/^[0-9a-f-]{36}$/);
  await submitPayment(page);
  await expect(page.locator('#alert-message')).toHaveText(/Payment successful\s*$/);

  // Re-POST the identical form (same request uuid), e.g. a browser resubmit.
  const replay = await page.request.post('/payment', {
    form: { account_num: 'add', contact_account_num: recipient, contact_label: '', amount: '30.00', uuid },
  });
  expect(new URL(replay.url()).searchParams.get('msg')).toBe('Payment failed: duplicate transaction uuid');

  await expectBalance(page, '$70.00');
  await expect(historyRows(page)).toHaveCount(2);
  expect(ledgerRowsInvolving(recipient)).toEqual([
    { fromAcct: customer.account, fromRoute: LOCAL_ROUTING, toAcct: recipient, toRoute: LOCAL_ROUTING, amount: 3000 },
  ]);
});
