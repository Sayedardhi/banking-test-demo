"""Unit tests for the contacts Flask API.

The database boundary (ContactsDb) is mocked; JWT verification, validation,
duplicate/self checks, error mapping and logging run for real.
"""
# pylint: disable=redefined-outer-name,missing-function-docstring

import base64
import hashlib
import hmac
import json
import logging
from unittest.mock import patch

import jwt
import pytest
from sqlalchemy.exc import OperationalError, SQLAlchemyError

import contacts as contacts_module
from tests.helpers import (
    ALICE, BOB, EXTERNAL_ROUTING, LOCAL_ROUTING, auth, contact, generate_keypair,
    make_token,
)


@pytest.fixture
def service(service_env):
    """Create the app with a mocked ContactsDb; returns (client, app, db_mock)."""
    service_env("postgresql://unused:unused@127.0.0.1:1/unused")
    with patch.object(contacts_module, "ContactsDb") as db_class:
        app = contacts_module.create_app()
    db = db_class.return_value
    db.get_contacts.return_value = []
    return app.test_client(), app, db


@pytest.fixture
def alice_token(keypair):
    return make_token(keypair[0], ALICE["user"], ALICE["acct"])


# ---------------------------------------------------------------- startup


def test_startup_reads_configuration_from_environment(service, keypair):
    _, app, _ = service
    assert app.config["VERSION"] == "test-version"
    assert app.config["LOCAL_ROUTING"] == LOCAL_ROUTING
    assert app.config["PUBLIC_KEY"] == keypair[1].decode()


def test_startup_exits_when_database_connection_fails(service_env):
    service_env("postgresql://unused:unused@127.0.0.1:1/unused")
    with patch.object(contacts_module, "ContactsDb",
                      side_effect=OperationalError("connect", {}, Exception("down"))):
        with pytest.raises(SystemExit) as exc:
            contacts_module.create_app()
    assert exc.value.code == 1


def test_startup_with_tracing_enabled_instruments_flask(service_env, monkeypatch):
    service_env("postgresql://unused:unused@127.0.0.1:1/unused")
    monkeypatch.setenv("ENABLE_TRACING", "true")
    with patch.object(contacts_module, "ContactsDb"), \
            patch.object(contacts_module, "CloudTraceSpanExporter") as exporter, \
            patch.object(contacts_module, "FlaskInstrumentor") as instrumentor, \
            patch.object(contacts_module, "set_global_textmap"), \
            patch.object(contacts_module.trace, "set_tracer_provider"), \
            patch.object(contacts_module.trace, "get_tracer_provider"):
        app = contacts_module.create_app()
    exporter.assert_called_once_with()
    instrumentor.return_value.instrument_app.assert_called_once_with(app)


def test_version_and_ready_endpoints_are_unauthenticated(service):
    client, _, _ = service
    assert client.get("/version").get_data(as_text=True) == "test-version"
    ready = client.get("/ready")
    assert (ready.status_code, ready.get_data(as_text=True)) == (200, "ok")


# ---------------------------------------------------- authorization (GET)


def test_get_returns_only_authenticated_users_contacts(service, alice_token):
    client, _, db = service
    stored = [contact(label="Rent")]
    db.get_contacts.return_value = stored
    resp = client.get("/contacts/" + ALICE["user"], headers=auth(alice_token))
    assert resp.status_code == 200
    assert resp.get_json() == stored
    db.get_contacts.assert_called_once_with(ALICE["user"])


def test_get_other_users_contacts_is_denied_without_db_read(service, alice_token):
    client, _, db = service
    resp = client.get("/contacts/" + BOB["user"], headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (401, "authentication denied")
    db.get_contacts.assert_not_called()


def _bad_tokens(keypair):
    other_private, _ = generate_keypair()
    public_pem = keypair[1]
    return {
        "missing": None,
        "empty bearer": "",
        "garbage": "not-a-jwt",
        "expired": make_token(keypair[0], ALICE["user"], ALICE["acct"], expires_in=-60),
        "foreign signer": make_token(other_private, ALICE["user"], ALICE["acct"]),
        "alg none": jwt.encode({"user": ALICE["user"], "acct": ALICE["acct"]},
                               key=None, algorithm="none"),
        "HS256 with public key as secret": _hs256_confusion_token(public_pem),
    }


def _hs256_confusion_token(public_pem):
    """Algorithm-confusion attack: HMAC-sign with the RSA public key."""
    def b64(data):
        return base64.urlsafe_b64encode(data).rstrip(b"=")
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    body = b64(json.dumps({"user": ALICE["user"], "acct": ALICE["acct"],
                           "exp": 4102444800}).encode())
    signature = b64(hmac.new(public_pem, header + b"." + body, hashlib.sha256).digest())
    return (header + b"." + body + b"." + signature).decode()


BAD_TOKEN_CASES = ["missing", "empty bearer", "garbage", "expired", "foreign signer",
                   "alg none", "HS256 with public key as secret"]


@pytest.mark.parametrize("case", BAD_TOKEN_CASES)
def test_get_rejects_invalid_tokens(service, keypair, case):
    client, _, db = service
    token = _bad_tokens(keypair)[case]
    headers = {} if token is None else auth(token)
    resp = client.get("/contacts/" + ALICE["user"], headers=headers)
    assert resp.status_code == 401
    db.get_contacts.assert_not_called()


# --------------------------------------------------- authorization (POST)


@pytest.mark.parametrize("case", BAD_TOKEN_CASES)
def test_post_rejects_invalid_tokens_without_writing(service, keypair, case):
    client, _, db = service
    token = _bad_tokens(keypair)[case]
    headers = {} if token is None else auth(token)
    resp = client.post("/contacts/" + ALICE["user"], json=contact(), headers=headers)
    assert resp.status_code == 401
    db.add_contact.assert_not_called()


def test_post_to_other_users_contacts_is_denied_without_writing(service, alice_token):
    client, _, db = service
    resp = client.post("/contacts/" + BOB["user"], json=contact(), headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (401, "authentication denied")
    db.add_contact.assert_not_called()
    db.get_contacts.assert_not_called()


# ------------------------------------------------------------ happy path


def test_post_valid_external_contact_persists_exact_record(service, alice_token):
    client, _, db = service
    resp = client.post("/contacts/" + ALICE["user"], json=contact(), headers=auth(alice_token))
    assert resp.status_code == 201
    assert resp.get_json() == {}
    db.add_contact.assert_called_once_with({
        "username": ALICE["user"],
        "label": "Payroll",
        "account_num": "1234567890",
        "routing_num": EXTERNAL_ROUTING,
        "is_external": True,
    })


@pytest.mark.parametrize("label", ["A", "Bob", "Joint savings 2", "x" * 30, "9 lives"])
def test_post_accepts_valid_labels(service, alice_token, label):
    client, _, db = service
    resp = client.post("/contacts/" + ALICE["user"], json=contact(label=label),
                       headers=auth(alice_token))
    assert resp.status_code == 201
    assert db.add_contact.call_args.args[0]["label"] == label


def test_post_internal_contact_with_local_routing_is_accepted(service, alice_token):
    client, _, db = service
    body = contact(routing_num=LOCAL_ROUTING, is_external=False, account_num=BOB["acct"])
    resp = client.post("/contacts/" + ALICE["user"], json=body, headers=auth(alice_token))
    assert resp.status_code == 201
    db.add_contact.assert_called_once()


# ------------------------------------------------------------ validation


@pytest.mark.parametrize("missing", ["label", "account_num", "routing_num", "is_external"])
def test_post_missing_required_field_is_rejected(service, alice_token, missing):
    client, _, db = service
    body = contact()
    del body[missing]
    resp = client.post("/contacts/" + ALICE["user"], json=body, headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (400, "missing required field(s)")
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("account_num", [
    "123456789", "12345678901", "12345abcde", "", None, " 123456789",
    "1234567890\n", "１２３４５６７８９０", "12345-6789",
])
def test_post_invalid_account_number_is_rejected(service, alice_token, account_num):
    client, _, db = service
    resp = client.post("/contacts/" + ALICE["user"], json=contact(account_num=account_num),
                       headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (400, "invalid account number")
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("routing_num", [
    "12345678", "1234567890", "abcdefghi", "", None, "111000025\n",
])
def test_post_invalid_routing_number_is_rejected(service, alice_token, routing_num):
    client, _, db = service
    resp = client.post("/contacts/" + ALICE["user"], json=contact(routing_num=routing_num),
                       headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (400, "invalid routing number")
    db.add_contact.assert_not_called()


def test_post_external_contact_with_local_routing_is_rejected(service, alice_token):
    client, _, db = service
    resp = client.post("/contacts/" + ALICE["user"],
                       json=contact(routing_num=LOCAL_ROUTING, is_external=True),
                       headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (400, "invalid routing number")
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("label", [
    "", None, " Leading space", "x" * 31, "Bob!", "Rent;DROP", "<b>Bob</b>",
    "Ünïcode", "tab\tlabel",
])
def test_post_invalid_label_is_rejected(service, alice_token, label):
    client, _, db = service
    resp = client.post("/contacts/" + ALICE["user"], json=contact(label=label),
                       headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (400, "invalid account label")
    db.add_contact.assert_not_called()


def test_post_label_with_trailing_newline_is_rejected(service, alice_token):
    """Requirement: labels are alphanumeric and spaces only."""
    client, _, db = service
    resp = client.post("/contacts/" + ALICE["user"], json=contact(label="Alice\n"),
                       headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (400, "invalid account label")
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("body", [
    contact(account_num=1234567890),
    contact(routing_num=111000025),
    [contact()],
])
def test_post_malformed_payload_is_a_client_error(service, alice_token, body):
    """Malformed (non-string / non-object) input must be rejected as 4xx, not crash."""
    client, _, db = service
    resp = client.post("/contacts/" + ALICE["user"], json=body, headers=auth(alice_token))
    assert 400 <= resp.status_code < 500
    db.add_contact.assert_not_called()


# ---------------------------------------------------- self / duplicates


def test_post_self_as_contact_is_rejected(service, alice_token):
    client, _, db = service
    body = contact(account_num=ALICE["acct"], routing_num=LOCAL_ROUTING, is_external=False)
    resp = client.post("/contacts/" + ALICE["user"], json=body, headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (
        409, "may not add yourself to contacts")
    db.add_contact.assert_not_called()


def test_post_same_account_number_at_external_bank_is_not_self(service, alice_token):
    client, _, db = service
    body = contact(account_num=ALICE["acct"], routing_num=EXTERNAL_ROUTING, is_external=True)
    resp = client.post("/contacts/" + ALICE["user"], json=body, headers=auth(alice_token))
    assert resp.status_code == 201
    db.add_contact.assert_called_once()


def test_post_duplicate_account_and_routing_is_rejected(service, alice_token):
    client, _, db = service
    db.get_contacts.return_value = [contact(label="Existing")]
    resp = client.post("/contacts/" + ALICE["user"], json=contact(label="New label"),
                       headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (
        409, "account already exists as a contact")
    db.get_contacts.assert_called_once_with(ALICE["user"])
    db.add_contact.assert_not_called()


def test_post_duplicate_label_is_rejected(service, alice_token):
    client, _, db = service
    db.get_contacts.return_value = [contact(label="Payroll", account_num="5555555555")]
    resp = client.post("/contacts/" + ALICE["user"], json=contact(label="Payroll"),
                       headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (
        409, "contact already exists with that label")
    db.add_contact.assert_not_called()


def test_post_same_account_at_different_bank_is_not_duplicate(service, alice_token):
    client, _, db = service
    db.get_contacts.return_value = [contact(label="Other", routing_num="222000111")]
    resp = client.post("/contacts/" + ALICE["user"], json=contact(), headers=auth(alice_token))
    assert resp.status_code == 201
    db.add_contact.assert_called_once()


# ------------------------------------------------------- error handling


def test_get_database_failure_returns_500_without_details(service, alice_token):
    client, _, db = service
    db.get_contacts.side_effect = SQLAlchemyError("password=secret host=db")
    resp = client.get("/contacts/" + ALICE["user"], headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (
        500, "failed to retrieve contacts list")


def test_post_database_write_failure_returns_500(service, alice_token):
    client, _, db = service
    db.add_contact.side_effect = SQLAlchemyError("write failed")
    resp = client.post("/contacts/" + ALICE["user"], json=contact(), headers=auth(alice_token))
    assert (resp.status_code, resp.get_data(as_text=True)) == (500, "failed to add contact")


def test_post_database_read_failure_during_duplicate_check_blocks_write(service, alice_token):
    client, _, db = service
    db.get_contacts.side_effect = SQLAlchemyError("read failed")
    resp = client.post("/contacts/" + ALICE["user"], json=contact(), headers=auth(alice_token))
    assert resp.status_code == 500
    db.add_contact.assert_not_called()


# ----------------------------------------------------- PII in logs


def _capture_all_logs(app, caplog, level):
    app.logger.setLevel(level)
    caplog.set_level(level, logger=app.logger.name)


@pytest.mark.parametrize("level", [logging.INFO, logging.DEBUG], ids=["INFO", "DEBUG"])
def test_add_contact_does_not_log_account_or_routing_numbers(service, alice_token,
                                                             caplog, level):
    """PII handling: full account/routing numbers must never reach logs, at any level."""
    client, app, _ = service
    _capture_all_logs(app, caplog, level)
    resp = client.post("/contacts/" + ALICE["user"],
                       json=contact(account_num="4815162342", routing_num="026009593"),
                       headers=auth(alice_token))
    assert resp.status_code == 201
    assert caplog.records, "expected the service to emit log records"
    assert "4815162342" not in caplog.text
    assert "026009593" not in caplog.text


def test_rejected_contact_does_not_log_account_number(service, alice_token, caplog):
    client, app, _ = service
    _capture_all_logs(app, caplog, logging.INFO)
    resp = client.post("/contacts/" + ALICE["user"],
                       json=contact(account_num="4815162342", label="bad!"),
                       headers=auth(alice_token))
    assert resp.status_code == 400
    assert "Error adding contact" in caplog.text
    assert "4815162342" not in caplog.text


def test_get_contacts_does_not_log_stored_numbers_or_token(service, alice_token, caplog):
    client, app, db = service
    db.get_contacts.return_value = [contact(account_num="4815162342")]
    _capture_all_logs(app, caplog, logging.DEBUG)
    client.get("/contacts/" + ALICE["user"], headers=auth(alice_token))
    assert "4815162342" not in caplog.text
    assert alice_token not in caplog.text
