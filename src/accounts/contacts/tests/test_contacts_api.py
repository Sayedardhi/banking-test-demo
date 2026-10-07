"""Unit tests for the contacts Flask API (contacts.py).

ContactsDb is replaced with a mock at the database boundary; JWT verification, request
sanitisation, validation and the duplicate/self-reference rules run for real.
"""
# pylint: disable=redefined-outer-name
import base64
import hashlib
import hmac
import json
import logging
from unittest import mock

import pytest
from sqlalchemy.exc import IntegrityError, OperationalError, SQLAlchemyError

import contacts
from support import ALICE, BOB, EXTERNAL_ROUTING, LOCAL_ROUTING, new_contact


@pytest.fixture
def db():
    """The ContactsDb instance the app would use; starts with no saved contacts."""
    instance = mock.Mock(name="ContactsDb()")
    instance.get_contacts.return_value = []
    return instance


@pytest.fixture
def client(service_env, db):  # pylint: disable=unused-argument
    with mock.patch.object(contacts, "ContactsDb", return_value=db) as factory:
        app = contacts.create_app()
    factory.assert_called_once_with("postgresql://unused:unused@127.0.0.1:1/unused", app.logger)
    app.testing = True
    return app.test_client()


def post(client, headers, body, user=ALICE["user"]):
    return client.post(f"/contacts/{user}", headers=headers, json=body)


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


# --- service endpoints and startup -------------------------------------------------------

def test_ready_and_version_need_no_auth(client):
    assert (client.get("/ready").status_code, client.get("/ready").text) == (200, "ok")
    assert (client.get("/version").status_code, client.get("/version").text) == (200, "v-test")


def test_startup_exits_when_database_connection_fails(service_env, caplog):  # pylint: disable=unused-argument
    error = OperationalError("connect", {}, Exception("connection refused"))
    with mock.patch.object(contacts, "ContactsDb", side_effect=error), pytest.raises(SystemExit) as stop:
        contacts.create_app()
    assert stop.value.code == 1
    assert "database connection failed" in caplog.text


def test_tracing_enabled_instruments_flask_and_exports_to_cloud_trace(service_env, db):
    service_env.setenv("ENABLE_TRACING", "true")
    with mock.patch.object(contacts, "ContactsDb", return_value=db), \
            mock.patch.object(contacts, "CloudTraceSpanExporter") as exporter, \
            mock.patch.object(contacts, "BatchSpanProcessor") as processor, \
            mock.patch.object(contacts, "FlaskInstrumentor") as instrumentor, \
            mock.patch.object(contacts, "trace") as trace, \
            mock.patch.object(contacts, "set_global_textmap") as textmap:
        app = contacts.create_app()
    processor.assert_called_once_with(exporter.return_value)
    trace.get_tracer_provider.return_value.add_span_processor.assert_called_once_with(processor.return_value)
    instrumentor.return_value.instrument_app.assert_called_once_with(app)
    textmap.assert_called_once()


def test_tracing_disabled_does_not_instrument(client):  # pylint: disable=unused-argument
    with mock.patch.object(contacts, "FlaskInstrumentor") as instrumentor:
        assert client.get("/ready").status_code == 200
    instrumentor.assert_not_called()


# --- authentication / authorization (both endpoints) -----------------------------------

def _forged_hs256(public_pem, claims):
    """HS256 token keyed with the RS256 public key (algorithm-confusion attack)."""
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    body = b64(json.dumps(claims).encode())
    sig = hmac.new(public_pem, f"{header}.{body}".encode(), hashlib.sha256).digest()
    return f"{header}.{body}.{b64(sig)}"


def _unsigned(claims):
    return b64(b'{"alg":"none","typ":"JWT"}') + "." + b64(json.dumps(claims).encode()) + "."


BAD_TOKENS = {
    "no header": lambda mint, keys: None,
    "empty bearer": lambda mint, keys: "Bearer ",
    "garbage": lambda mint, keys: "Bearer not.a.jwt",
    "expired": lambda mint, keys: "Bearer " + mint(expires_in=-60),
    "untrusted signer": lambda mint, keys: "Bearer " + mint(key=keys[0]),
    "alg none": lambda mint, keys: "Bearer " + _unsigned(ALICE),
}


@pytest.mark.parametrize("method", ["GET", "POST"])
@pytest.mark.parametrize("case", BAD_TOKENS)
def test_invalid_credentials_are_denied_without_touching_the_database(client, db, make_token,
                                                                    foreign_keys, method, case):
    header = BAD_TOKENS[case](make_token, foreign_keys)
    headers = {"Authorization": header} if header else {}
    res = client.open(f"/contacts/{ALICE['user']}", method=method, headers=headers, json=new_contact())
    assert (res.status_code, res.text) == (401, "authentication denied")
    db.get_contacts.assert_not_called()
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("method", ["GET", "POST"])
def test_hs256_token_forged_with_the_public_key_is_denied(client, db, signing_keys, method):
    token = _forged_hs256(signing_keys[1], {**ALICE, "exp": 4102444800})
    res = client.open(f"/contacts/{ALICE['user']}", method=method,
                      headers={"Authorization": "Bearer " + token}, json=new_contact())
    assert res.status_code == 401
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("method", ["GET", "POST"])
def test_valid_token_cannot_read_or_write_another_users_contacts(client, db, auth, method):
    res = client.open(f"/contacts/{BOB['user']}", method=method, headers=auth(ALICE), json=new_contact())
    assert (res.status_code, res.text) == (401, "authentication denied")
    db.get_contacts.assert_not_called()
    db.add_contact.assert_not_called()


# --- GET /contacts/<username> -----------------------------------------------------------

def test_get_returns_the_users_saved_contacts(client, db, auth):
    saved = [new_contact(), new_contact(label="Landlord", account_num="1044226144",
                                        routing_num=LOCAL_ROUTING, is_external=False)]
    db.get_contacts.return_value = saved
    res = client.get("/contacts/alice", headers=auth())
    assert res.status_code == 200
    assert res.get_json() == saved
    db.get_contacts.assert_called_once_with("alice")


@pytest.mark.parametrize("error", [SQLAlchemyError("boom"), OperationalError("SELECT", {}, Exception("down"))])
def test_get_database_failure_returns_500(client, db, auth, error):
    db.get_contacts.side_effect = error
    res = client.get("/contacts/alice", headers=auth())
    assert (res.status_code, res.text) == (500, "failed to retrieve contacts list")


# --- POST /contacts/<username>: accepted contacts ----------------------------------------

def test_valid_external_contact_is_stored_for_the_token_user_only(client, db, auth):
    # Extra/foreign fields in the body (e.g. another username) must not reach the database.
    body = new_contact(username="bob", account_num="0000000001", admin=True)
    res = post(client, auth(), body)
    assert (res.status_code, res.get_json()) == (201, {})
    db.add_contact.assert_called_once_with({"username": "alice", "label": "Credit Union",
                                            "account_num": "0000000001",
                                            "routing_num": EXTERNAL_ROUTING, "is_external": True})


@pytest.mark.parametrize("label", ["A", "9", "a" * 30, "Rent 2026", "Mum and Dad ", "X Y Z"])
def test_label_boundaries_accepted(client, db, auth, label):
    assert post(client, auth(), new_contact(label=label)).status_code == 201
    assert db.add_contact.call_args.args[0]["label"] == label


def test_internal_contact_with_local_routing_is_allowed(client, db, auth):
    body = new_contact(account_num=BOB["acct"], routing_num=LOCAL_ROUTING, is_external=False)
    assert post(client, auth(), body).status_code == 201
    assert db.add_contact.call_args.args[0]["is_external"] is False


def test_own_account_number_at_another_bank_is_not_self_reference(client, auth):
    body = new_contact(account_num=ALICE["acct"], routing_num=EXTERNAL_ROUTING)
    assert post(client, auth(), body).status_code == 201


def test_same_account_at_a_different_bank_is_not_a_duplicate(client, db, auth):
    db.get_contacts.return_value = [new_contact()]
    body = new_contact(label="Other Bank", routing_num="011000015")
    assert post(client, auth(), body).status_code == 201


# --- POST: validation (400) and business-rule rejections (409) ---------------------------

@pytest.mark.parametrize("missing", ["label", "account_num", "routing_num", "is_external"])
def test_missing_required_field_is_rejected(client, db, auth, missing):
    body = new_contact()
    del body[missing]
    res = post(client, auth(), body)
    assert (res.status_code, res.text) == (400, "missing required field(s)")
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("account", [None, "", "123456789", "12345678901", "12345abcde", "12345 6789",
                                     "-123456789", "1234567890\n", "１２３４５６７８９０", "<b>123456</b>"])
def test_malformed_account_number_is_rejected(client, db, auth, account):
    res = post(client, auth(), new_contact(account_num=account))
    assert (res.status_code, res.text) == (400, "invalid account number")
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("routing", [None, "", "80888958", "8088895880", "80888958a", "808889588\n"])
def test_malformed_routing_number_is_rejected(client, db, auth, routing):
    res = post(client, auth(), new_contact(routing_num=routing))
    assert (res.status_code, res.text) == (400, "invalid routing number")
    db.add_contact.assert_not_called()


def test_external_contact_cannot_use_this_banks_routing_number(client, db, auth):
    res = post(client, auth(), new_contact(routing_num=LOCAL_ROUTING, is_external=True))
    assert (res.status_code, res.text) == (400, "invalid routing number")
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("label", [None, "", " Leading", "a" * 31, "Bob!", "O'Brien", "José",
                                   "Tab\tSeparated", "<script>alert(1)</script>", "Robert'); DROP TABLE contacts;--"])
def test_invalid_label_is_rejected(client, db, auth, label):
    res = post(client, auth(), new_contact(label=label))
    assert (res.status_code, res.text) == (400, "invalid account label")
    db.add_contact.assert_not_called()


def test_label_with_trailing_newline_is_rejected(client, db, auth):
    """Labels are alphanumeric and spaces only; a newline must not be stored or rendered."""
    res = post(client, auth(), new_contact(label="Landlord\n"))
    assert (res.status_code, res.text) == (400, "invalid account label")
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("field,value,message", [
    ("account_num", 9099791699, "invalid account number"),
    ("routing_num", 808889588, "invalid routing number"),
])
def test_numeric_json_account_or_routing_is_rejected_as_invalid(client, db, auth, field, value, message):
    """A JSON number instead of a digit string is invalid input (400), not a server error."""
    res = post(client, auth(), new_contact(**{field: value}))
    assert (res.status_code, res.text) == (400, message)
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("flag", ["false", None])
def test_non_boolean_is_external_is_rejected(client, db, auth, flag):
    """is_external decides whether the local routing number is allowed; it must be a real boolean."""
    res = post(client, auth(), new_contact(is_external=flag))
    assert res.status_code == 400
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("body", [[], "contact"])
def test_non_object_json_body_is_rejected_as_bad_request(client, db, auth, body):
    res = client.post("/contacts/alice", headers=auth(), data=json.dumps(body), content_type="application/json")
    assert res.status_code == 400
    db.add_contact.assert_not_called()


def test_non_json_body_is_refused_without_writing(client, db, auth):
    res = client.post("/contacts/alice", headers=auth(), data="label=x", content_type="application/x-www-form-urlencoded")
    assert res.status_code == 415
    db.add_contact.assert_not_called()


def test_cannot_add_yourself(client, db, auth):
    body = new_contact(account_num=ALICE["acct"], routing_num=LOCAL_ROUTING, is_external=False)
    res = post(client, auth(), body)
    assert (res.status_code, res.text) == (409, "may not add yourself to contacts")
    db.get_contacts.assert_not_called()
    db.add_contact.assert_not_called()


def test_duplicate_account_and_routing_is_rejected(client, db, auth):
    db.get_contacts.return_value = [new_contact(label="Original")]
    res = post(client, auth(), new_contact(label="Second Name"))
    assert (res.status_code, res.text) == (409, "account already exists as a contact")
    db.get_contacts.assert_called_once_with("alice")
    db.add_contact.assert_not_called()


def test_duplicate_label_is_rejected(client, db, auth):
    db.get_contacts.return_value = [new_contact(account_num="1111111111")]
    res = post(client, auth(), new_contact(account_num="2222222222"))
    assert (res.status_code, res.text) == (409, "contact already exists with that label")
    db.add_contact.assert_not_called()


@pytest.mark.parametrize("stage", ["get_contacts", "add_contact"])
def test_post_database_failure_returns_500(client, db, auth, stage):
    getattr(db, stage).side_effect = SQLAlchemyError("boom")
    res = post(client, auth(), new_contact())
    assert (res.status_code, res.text) == (500, "failed to add contact")
    if stage == "get_contacts":
        db.add_contact.assert_not_called()


# --- PII in logs at the production level (LOG_LEVEL=info) --------------------------------

def _logged_at_info(caplog):
    return "\n".join(r.getMessage() for r in caplog.records if r.levelno >= logging.INFO)


@pytest.mark.parametrize("body", [
    new_contact(routing_num=LOCAL_ROUTING),            # 400
    new_contact(label="Bad!"),                         # 400
])
def test_rejected_contacts_do_not_log_account_numbers(client, auth, caplog, body):
    caplog.set_level(logging.INFO)
    assert post(client, auth(), body).status_code == 400
    assert body["account_num"] not in _logged_at_info(caplog)


def test_database_error_on_insert_does_not_log_account_numbers(client, db, auth, caplog):
    """Driver errors carry the bound parameters; the full account number must not reach the logs."""
    body = new_contact()
    db.add_contact.side_effect = IntegrityError(
        "INSERT INTO contacts (username, label, account_num, routing_num, is_external) VALUES (...)",
        {"username": "alice", **body}, Exception("violates foreign key constraint"))
    caplog.set_level(logging.INFO)
    assert post(client, auth(), body).status_code == 500
    assert body["account_num"] not in _logged_at_info(caplog)
