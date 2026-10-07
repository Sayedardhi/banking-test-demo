"""Payment and deposit processing: validation, ledgerwriter submission, audit emission, logging."""
import json
import logging

import pytest
import requests

from tests.fakes import redirect_msg, response
from tests.helpers import (ACCOUNT, AUDIT_TOKEN, EXTERNAL_ACCOUNT, EXTERNAL_ROUTING, LOCAL_ROUTING,
                           OTHER_ACCOUNT)

LEDGER = 'http://ledgerwriter.test:8080/transactions'
AUDIT = 'http://audit.test:8080/events'
CONTACTS = 'http://contacts.test:8080/contacts/alice_test'
UUID = '7f1c2a9e-3b4d-4e5f-8a6b-9c0d1e2f3a4b'


@pytest.fixture
def ok_backends(backends):
    backends.on('POST', LEDGER, response(201, text='ok'))
    backends.on('POST', AUDIT, response(201, {'status': 'recorded'}))
    backends.on('POST', CONTACTS, response(201, text=''))
    return backends


def pay(client, amount='12.34', to=OTHER_ACCOUNT, uuid=UUID, **extra):
    return client.post('/payment', data={'account_num': to, 'amount': amount, 'uuid': uuid, **extra})


def deposit_new(client, amount='50.00', acct=EXTERNAL_ACCOUNT, routing=EXTERNAL_ROUTING,
                label='', uuid=UUID):
    return client.post('/deposit', data={'account': 'add', 'external_account_num': acct,
                                         'external_routing_num': routing, 'external_label': label,
                                         'amount': amount, 'uuid': uuid})


def deposit_saved(client, acct=EXTERNAL_ACCOUNT, routing=EXTERNAL_ROUTING, amount='50.00'):
    saved = json.dumps({'account_num': acct, 'routing_num': routing})
    return client.post('/deposit', data={'account': saved, 'amount': amount, 'uuid': UUID})


def assert_home(resp, msg, code=303):
    assert resp.status_code == code, resp.data[:200]
    assert resp.headers['Location'].split('?')[0].endswith('/home')
    assert redirect_msg(resp) == msg


class TestPayment:
    def test_submits_exact_ledger_transaction_in_cents(self, auth_client, ok_backends, token):
        assert_home(pay(auth_client, '12.34'), 'Payment successful')
        (_, url, kwargs), = ok_backends.posted(LEDGER)
        assert url == LEDGER
        assert json.loads(kwargs['data']) == {
            'fromAccountNum': ACCOUNT, 'fromRoutingNum': LOCAL_ROUTING,
            'toAccountNum': OTHER_ACCOUNT, 'toRoutingNum': LOCAL_ROUTING,
            'amount': 1234, 'uuid': UUID}
        assert kwargs['headers'] == {'Authorization': 'Bearer ' + token,
                                     'content-type': 'application/json'}
        assert kwargs['timeout'] == 4

    @pytest.mark.parametrize('amount,cents', [('0.01', 1), ('1', 100), ('1000000.00', 100000000),
                                              ('19.99', 1999), (' 7.5 ', 750)])
    def test_decimal_amounts_convert_to_exact_cents(self, auth_client, ok_backends, amount, cents):
        assert_home(pay(auth_client, amount), 'Payment successful')
        assert ok_backends.posted_json(LEDGER)[0]['amount'] == cents

    @pytest.mark.parametrize('amount', ['abc', '', '12,50', '1.2.3', 'NaN', '$5'])
    def test_non_numeric_amount_is_rejected_before_ledger(self, auth_client, ok_backends, amount):
        resp = pay(auth_client, amount)
        assert resp.status_code == 302 and redirect_msg(resp) == 'Payment failed'
        assert ok_backends.calls == []

    @pytest.mark.parametrize('amount', ['Infinity', '-Infinity'])
    def test_infinite_amount_is_rejected_gracefully(self, auth_client, ok_backends, amount):
        """FINDING: int(Decimal('Infinity')) raises OverflowError, which payment() does not catch."""
        resp = pay(auth_client, amount)
        assert resp.status_code == 302, f'expected a failed-payment redirect, got {resp.status_code}'
        assert redirect_msg(resp) == 'Payment failed'
        assert ok_backends.calls == []

    @pytest.mark.parametrize('ledger_status,body', [(400, 'invalid amount'),
                                                    (400, 'sender and receiver cannot be the same'),
                                                    (401, 'sender not authenticated'),
                                                    (500, 'ledger unavailable')])
    def test_ledger_rejection_is_shown_and_not_audited(self, auth_client, ok_backends,
                                                       ledger_status, body):
        ok_backends.on('POST', LEDGER, response(ledger_status, text=body))
        resp = pay(auth_client, '-5.00')
        assert_home(resp, 'Payment failed: ' + body, code=302)
        assert ok_backends.posted(AUDIT) == [], 'failed transactions must not be audited'

    @pytest.mark.parametrize('error', [requests.exceptions.ConnectionError('refused'),
                                       requests.exceptions.Timeout('slow')])
    def test_ledger_unreachable_fails_generically(self, auth_client, ok_backends, error):
        ok_backends.on('POST', LEDGER, error)
        assert_home(pay(auth_client), 'Payment failed', code=302)
        assert ok_backends.posted(AUDIT) == []

    @pytest.mark.parametrize('missing', ['account_num', 'amount', 'uuid'])
    def test_missing_field_is_a_bad_request(self, auth_client, ok_backends, missing):
        form = {'account_num': OTHER_ACCOUNT, 'amount': '1.00', 'uuid': UUID}
        del form[missing]
        assert auth_client.post('/payment', data=form).status_code == 400
        assert ok_backends.calls == []

    def test_new_recipient_with_label_is_saved_as_internal_contact_first(self, auth_client,
                                                                          ok_backends):
        resp = pay(auth_client, to='add', contact_account_num=OTHER_ACCOUNT, contact_label='Rent')
        assert_home(resp, 'Payment successful')
        assert [c[1] for c in ok_backends.calls] == [CONTACTS, LEDGER, AUDIT]
        assert ok_backends.posted_json(CONTACTS) == [{'label': 'Rent', 'account_num': OTHER_ACCOUNT,
                                                      'routing_num': LOCAL_ROUTING,
                                                      'is_external': False}]
        assert ok_backends.posted_json(LEDGER)[0]['toAccountNum'] == OTHER_ACCOUNT

    def test_new_recipient_without_label_is_not_saved(self, auth_client, ok_backends):
        pay(auth_client, to='add', contact_account_num=OTHER_ACCOUNT, contact_label='')
        assert ok_backends.posted(CONTACTS) == []
        assert ok_backends.posted_json(LEDGER)[0]['toAccountNum'] == OTHER_ACCOUNT

    def test_contact_rejection_stops_payment(self, auth_client, ok_backends):
        ok_backends.on('POST', CONTACTS, response(409, text='account already exists as a contact'))
        resp = pay(auth_client, to='add', contact_account_num=OTHER_ACCOUNT, contact_label='Rent')
        assert_home(resp, 'Payment failed: account already exists as a contact', code=302)
        assert ok_backends.posted(LEDGER) == []


class TestDeposit:
    def test_external_deposit_credits_own_account(self, auth_client, ok_backends):
        assert_home(deposit_new(auth_client, '250.10'), 'Deposit successful')
        assert ok_backends.posted_json(LEDGER) == [{
            'fromAccountNum': EXTERNAL_ACCOUNT, 'fromRoutingNum': EXTERNAL_ROUTING,
            'toAccountNum': ACCOUNT, 'toRoutingNum': LOCAL_ROUTING, 'amount': 25010, 'uuid': UUID}]

    def test_saved_external_account_is_used(self, auth_client, ok_backends):
        assert_home(deposit_saved(auth_client), 'Deposit successful')
        sent = ok_backends.posted_json(LEDGER)[0]
        assert (sent['fromAccountNum'], sent['fromRoutingNum']) == (EXTERNAL_ACCOUNT,
                                                                    EXTERNAL_ROUTING)

    def test_new_external_account_with_local_routing_is_rejected(self, auth_client, ok_backends):
        resp = deposit_new(auth_client, acct=OTHER_ACCOUNT, routing=LOCAL_ROUTING, label='Mine')
        assert_home(resp, 'Deposit failed: invalid routing number', code=302)
        assert ok_backends.calls == [], 'no contact, ledger or audit call for a rejected deposit'

    def test_saved_account_with_local_routing_is_rejected(self, auth_client, ok_backends):
        """FINDING: the local-routing check only guards new accounts; a crafted saved-account value
        pulls from another local account and reaches ledgerwriter."""
        resp = deposit_saved(auth_client, acct=OTHER_ACCOUNT, routing=LOCAL_ROUTING)
        assert_home(resp, 'Deposit failed: invalid routing number', code=302)
        assert ok_backends.posted(LEDGER) == []

    def test_new_external_account_with_label_is_saved_as_external(self, auth_client, ok_backends):
        deposit_new(auth_client, label='Payroll')
        assert ok_backends.posted_json(CONTACTS) == [{'label': 'Payroll',
                                                      'account_num': EXTERNAL_ACCOUNT,
                                                      'routing_num': EXTERNAL_ROUTING,
                                                      'is_external': True}]

    @pytest.mark.parametrize('amount', ['abc', '', 'Infinity'])
    def test_invalid_amount_is_rejected_gracefully(self, auth_client, ok_backends, amount):
        """FINDING: deposit() does not catch ValueError/DecimalException/OverflowError -> HTTP 500."""
        resp = deposit_new(auth_client, amount)
        assert resp.status_code == 302, f'expected a failed-deposit redirect, got {resp.status_code}'
        assert redirect_msg(resp) == 'Deposit failed'
        assert ok_backends.posted(LEDGER) == []

    def test_ledger_rejection_is_shown_and_not_audited(self, auth_client, ok_backends):
        ok_backends.on('POST', LEDGER, response(400, text='invalid amount'))
        assert_home(deposit_new(auth_client, '0'), 'Deposit failed: invalid amount', code=302)
        assert ok_backends.posted(AUDIT) == []

    def test_ledger_unreachable_fails_generically(self, auth_client, ok_backends):
        ok_backends.on('POST', LEDGER, requests.exceptions.ConnectionError('refused'))
        assert_home(deposit_new(auth_client), 'Deposit failed', code=302)
        assert ok_backends.posted(AUDIT) == []


class TestAuditEmission:
    @pytest.mark.parametrize('action', ['payment', 'deposit'])
    def test_confirmed_transaction_emits_one_matching_event(self, auth_client, ok_backends, action):
        resp = pay(auth_client, '3.21') if action == 'payment' else deposit_new(auth_client, '3.21')
        assert redirect_msg(resp) == action.capitalize() + ' successful'
        ledger = ok_backends.posted_json(LEDGER)[0]
        (_, _, kwargs), = ok_backends.posted(AUDIT)
        assert kwargs['json'] == {'eventId': UUID, 'action': action, 'outcome': 'succeeded',
                                  'amountCents': 321, 'fromAccount': ledger['fromAccountNum'],
                                  'toAccount': ledger['toAccountNum']}
        assert kwargs['headers'] == {'Authorization': 'Bearer ' + AUDIT_TOKEN}
        assert kwargs['timeout'] == 2
        assert [c[1] for c in ok_backends.calls] == [LEDGER, AUDIT], 'audit only after the ledger'

    def test_event_carries_no_credentials_or_customer_profile(self, auth_client, ok_backends, token):
        pay(auth_client)
        event = json.dumps(ok_backends.posted(AUDIT)[0][2]['json'])
        for secret in (token, 'Alice Tester', 'alice_test', 'password', AUDIT_TOKEN):
            assert secret not in event

    @pytest.mark.parametrize('failure', [response(500, text='db locked'), response(401, text='no'),
                                         requests.exceptions.Timeout('slow'),
                                         requests.exceptions.ConnectionError('refused')],
                             ids=['500', '401', 'timeout', 'connection-error'])
    @pytest.mark.parametrize('action', ['payment', 'deposit'])
    def test_audit_outage_keeps_transaction_and_flags_it(self, auth_client, ok_backends, failure,
                                                         action, caplog):
        ok_backends.on('POST', AUDIT, failure)
        resp = pay(auth_client) if action == 'payment' else deposit_new(auth_client)
        assert_home(resp, action.capitalize() + ' successful; audit recording unavailable')
        assert len(ok_backends.posted(LEDGER)) == 1
        assert 'Confirmed transaction audit delivery failed' in caplog.text

    def test_audit_disabled_when_url_unset(self, env, make_app, backends, token):
        env.delenv('AUDIT_SERVICE_URL')
        backends.on('POST', LEDGER, response(201, text='ok'))
        client = make_app().test_client()
        client.set_cookie('token', token)
        assert redirect_msg(pay(client)) == 'Payment successful'
        assert [c[1] for c in backends.calls] == [LEDGER]

    def test_missing_audit_token_still_sends_bearer_scheme(self, env, make_app, ok_backends, token):
        env.delenv('AUDIT_TOKEN')
        client = make_app().test_client()
        client.set_cookie('token', token)
        pay(client)
        assert ok_backends.posted(AUDIT)[0][2]['headers'] == {'Authorization': 'Bearer '}


class TestSensitiveLogging:
    def test_logs_never_contain_tokens_accounts_or_credentials(self, auth_client, ok_backends,
                                                               token, caplog):
        caplog.set_level(logging.DEBUG)
        pay(auth_client, '12.34')
        deposit_new(auth_client, '5', label='Payroll')
        ok_backends.on('POST', AUDIT, requests.exceptions.ConnectionError('refused'))
        pay(auth_client, '1.00')
        auth_client.post('/payment', data={'account_num': OTHER_ACCOUNT, 'amount': 'x', 'uuid': UUID})
        assert caplog.records, 'expected the flows to log'
        for value in (token, AUDIT_TOKEN, ACCOUNT, OTHER_ACCOUNT, EXTERNAL_ACCOUNT, UUID):
            assert value not in caplog.text

    def test_login_failure_does_not_log_password(self, client, backends, caplog):
        caplog.set_level(logging.DEBUG)
        backends.on('GET', 'http://userservice.test:8080/login',
                    response(401, text='', url='http://userservice.test:8080/login'))
        client.post('/login', data={'username': 'alice_test', 'password': 'Sup3r-Secret!'})
        assert 'Sup3r-Secret!' not in caplog.text
