"""Frontend <-> audit service <-> ledger boundary over real HTTP and a real SQLite audit store."""
import json
import uuid

import pytest
import requests

from tests.helpers import ACCOUNT, EXTERNAL_ACCOUNT, EXTERNAL_ROUTING, LOCAL_ROUTING, OTHER_ACCOUNT

pytestmark = pytest.mark.integration


def masked(account):
    return '******' + account[-4:]


def post(session, base, path, data):
    return session.post(base + path, data=data, allow_redirects=False, timeout=15)


def msg(resp):
    from urllib.parse import parse_qs, urlparse  # pylint: disable=import-outside-toplevel
    return parse_qs(urlparse(resp.headers['Location']).query)['msg'][0]


def audit_for(audit_service, event_id):
    return [e for e in audit_service.events() if e['eventId'] == event_id]


class TestConfirmedTransactions:
    def test_payment_reaches_ledger_and_persists_one_masked_audit_record(self, frontend, customer,
                                                                         bank, audit_service):
        event_id = str(uuid.uuid4())
        resp = post(customer, frontend, '/payment',
                    {'account_num': OTHER_ACCOUNT, 'amount': '42.17', 'uuid': event_id})
        assert resp.status_code == 303 and msg(resp) == 'Payment successful'
        (ledger,) = bank.ledger_posts()
        assert json.loads(ledger['body']) == {
            'fromAccountNum': ACCOUNT, 'fromRoutingNum': LOCAL_ROUTING,
            'toAccountNum': OTHER_ACCOUNT, 'toRoutingNum': LOCAL_ROUTING,
            'amount': 4217, 'uuid': event_id}
        assert ledger['headers']['Authorization'] == 'Bearer ' + customer.cookies['token']
        (record,) = audit_for(audit_service, event_id)
        assert {k: v for k, v in record.items() if k != 'recordedAt'} == {
            'eventId': event_id, 'action': 'payment', 'outcome': 'succeeded', 'amountCents': 4217,
            'fromAccount': masked(ACCOUNT), 'toAccount': masked(OTHER_ACCOUNT)}

    def test_deposit_is_audited_with_external_source_masked(self, frontend, customer, bank,
                                                            audit_service):
        event_id = str(uuid.uuid4())
        resp = post(customer, frontend, '/deposit', {
            'account': 'add', 'external_account_num': EXTERNAL_ACCOUNT,
            'external_routing_num': EXTERNAL_ROUTING, 'external_label': '', 'amount': '1500',
            'uuid': event_id})
        assert msg(resp) == 'Deposit successful'
        assert len(bank.ledger_posts()) == 1
        (record,) = audit_for(audit_service, event_id)
        assert (record['action'], record['amountCents']) == ('deposit', 150000)
        assert (record['fromAccount'], record['toAccount']) == (masked(EXTERNAL_ACCOUNT),
                                                                masked(ACCOUNT))

    def test_raw_account_numbers_never_reach_the_audit_database(self, frontend, customer,
                                                                audit_service):
        post(customer, frontend, '/payment',
             {'account_num': OTHER_ACCOUNT, 'amount': '1.00', 'uuid': str(uuid.uuid4())})
        stored = b''.join(p.read_bytes() for p in audit_service.db.parent.glob('audit.sqlite*'))
        assert masked(OTHER_ACCOUNT).encode() in stored
        for raw in (ACCOUNT, OTHER_ACCOUNT, customer.cookies['token']):
            assert raw.encode() not in stored
        assert ACCOUNT not in audit_service.log.read_text()

    def test_home_reflects_the_confirmed_payment(self, frontend, customer, bank):
        post(customer, frontend, '/payment',
             {'account_num': OTHER_ACCOUNT, 'amount': '250.00', 'uuid': str(uuid.uuid4())})
        page = customer.get(frontend + '/home', timeout=15).text
        assert bank.balances[ACCOUNT] == 75000
        assert '$750.00' in page and '-$250.00' in page and OTHER_ACCOUNT in page


class TestRejectedTransactions:
    def test_ledger_rejection_leaves_no_audit_record(self, frontend, customer, bank,
                                                     audit_service):
        bank.ledger_status, bank.ledger_body = 400, 'insufficient balance'
        event_id = str(uuid.uuid4())
        resp = post(customer, frontend, '/payment',
                    {'account_num': OTHER_ACCOUNT, 'amount': '99999', 'uuid': event_id})
        assert msg(resp) == 'Payment failed: insufficient balance'
        assert audit_for(audit_service, event_id) == []
        assert bank.balances[ACCOUNT] == 100000

    @pytest.mark.parametrize('form', [
        {'account_num': OTHER_ACCOUNT, 'amount': 'ten dollars'},
        {'account': 'add', 'external_account_num': OTHER_ACCOUNT,
         'external_routing_num': LOCAL_ROUTING, 'amount': '10'},
    ], ids=['payment-non-numeric', 'deposit-local-routing'])
    def test_frontend_validation_blocks_ledger_and_audit(self, frontend, customer, bank,
                                                         audit_service, form):
        event_id = str(uuid.uuid4())
        path = '/payment' if 'account_num' in form else '/deposit'
        resp = post(customer, frontend, path, {**form, 'uuid': event_id})
        assert resp.status_code == 302 and 'failed' in msg(resp)
        assert bank.ledger_posts() == []
        assert audit_for(audit_service, event_id) == []

    def test_unauthenticated_transaction_is_rejected_with_no_side_effects(self, frontend, bank,
                                                                          audit_service):
        event_id = str(uuid.uuid4())
        resp = requests.post(frontend + '/payment', timeout=10, allow_redirects=False,
                             data={'account_num': OTHER_ACCOUNT, 'amount': '5', 'uuid': event_id},
                             cookies={'token': 'forged.token.value'})
        assert resp.status_code == 401
        assert bank.requests == [] and audit_for(audit_service, event_id) == []


class TestAuditDependencyFailures:
    def test_wrong_audit_token_keeps_transaction_and_flags_missing_audit(self, frontend, customer,
                                                                         bank, audit_service,
                                                                         monkeypatch):
        monkeypatch.setenv('AUDIT_TOKEN', 'wrong-token')
        event_id = str(uuid.uuid4())
        resp = post(customer, frontend, '/payment',
                    {'account_num': OTHER_ACCOUNT, 'amount': '3', 'uuid': event_id})
        assert msg(resp) == 'Payment successful; audit recording unavailable'
        assert len(bank.ledger_posts()) == 1
        assert audit_for(audit_service, event_id) == []

    def test_unreachable_audit_service_keeps_transaction(self, frontend, customer, bank,
                                                         monkeypatch):
        monkeypatch.setenv('AUDIT_SERVICE_URL', 'http://127.0.0.1:9')
        resp = post(customer, frontend, '/deposit', {
            'account': 'add', 'external_account_num': EXTERNAL_ACCOUNT,
            'external_routing_num': EXTERNAL_ROUTING, 'amount': '20', 'uuid': str(uuid.uuid4())})
        assert msg(resp) == 'Deposit successful; audit recording unavailable'
        assert len(bank.ledger_posts()) == 1

    def test_non_uuid_transaction_id_is_refused_by_audit_and_flagged(self, frontend, customer,
                                                                     bank, audit_service):
        resp = post(customer, frontend, '/payment',
                    {'account_num': OTHER_ACCOUNT, 'amount': '3', 'uuid': 'not-a-uuid'})
        assert msg(resp) == 'Payment successful; audit recording unavailable'
        assert len(bank.ledger_posts()) == 1
        assert audit_for(audit_service, 'not-a-uuid') == []

    def test_replayed_id_is_idempotent_but_conflicting_replay_is_flagged(self, frontend, customer,
                                                                         audit_service):
        event_id = str(uuid.uuid4())
        form = {'account_num': OTHER_ACCOUNT, 'amount': '8.00', 'uuid': event_id}
        assert msg(post(customer, frontend, '/payment', form)) == 'Payment successful'
        assert msg(post(customer, frontend, '/payment', form)) == 'Payment successful'
        tampered = post(customer, frontend, '/payment', {**form, 'amount': '800.00'})
        assert msg(tampered) == 'Payment successful; audit recording unavailable'
        (record,) = audit_for(audit_service, event_id)
        assert record['amountCents'] == 800, 'the original audit record must not be overwritten'
