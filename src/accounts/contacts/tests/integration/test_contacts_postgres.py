"""API-to-PostgreSQL integration tests for the contacts service.

Real Flask app + real ContactsDb + real accounts-db schema. Verifies persisted
state, that rejected operations leave the table unchanged, per-user isolation,
and documented behaviour when the database is unavailable.
"""
# pylint: disable=redefined-outer-name,missing-function-docstring,too-many-arguments,too-many-positional-arguments

import socket

import pytest

import contacts as contacts_module
from db import ContactsDb
from tests.helpers import (
    ALICE, BOB, EXTERNAL_ROUTING, LOCAL_ROUTING, auth, contact, make_token,
)


@pytest.fixture
def alice(keypair):
    return auth(make_token(keypair[0], ALICE["user"], ALICE["acct"]))


@pytest.fixture
def bob(keypair):
    return auth(make_token(keypair[0], BOB["user"], BOB["acct"]))


def test_added_contact_is_persisted_and_returned(client, alice, rows):
    resp = client.post("/contacts/" + ALICE["user"], json=contact(), headers=alice)
    assert resp.status_code == 201
    assert rows() == [{
        "username": ALICE["user"], "label": "Payroll", "account_num": "1234567890",
        "routing_num": EXTERNAL_ROUTING, "is_external": True,
    }]
    listed = client.get("/contacts/" + ALICE["user"], headers=alice)
    assert listed.status_code == 200
    assert listed.get_json() == [contact()]


def test_internal_contact_round_trips_boolean_false(client, alice, rows):
    body = contact(label="Bob", account_num=BOB["acct"], routing_num=LOCAL_ROUTING,
                   is_external=False)
    assert client.post("/contacts/" + ALICE["user"], json=body, headers=alice).status_code == 201
    assert rows()[0]["is_external"] is False
    assert client.get("/contacts/" + ALICE["user"], headers=alice).get_json() == [body]


def test_users_cannot_read_or_write_each_others_contacts(client, alice, bob, rows):
    client.post("/contacts/" + ALICE["user"], json=contact(label="Alice private"), headers=alice)
    client.post("/contacts/" + BOB["user"],
                json=contact(label="Bob private", account_num="2222222222"), headers=bob)

    assert [c["label"] for c in client.get("/contacts/" + BOB["user"], headers=bob).get_json()] \
        == ["Bob private"]
    assert client.get("/contacts/" + ALICE["user"], headers=bob).status_code == 401
    assert client.post("/contacts/" + ALICE["user"], json=contact(label="Injected"),
                       headers=bob).status_code == 401
    assert [r["label"] for r in rows(ALICE["user"])] == ["Alice private"]


@pytest.mark.parametrize("name,body,status", [
    ("invalid account", contact(account_num="12345"), 400),
    ("invalid routing", contact(routing_num="12"), 400),
    ("external with local routing", contact(routing_num=LOCAL_ROUTING), 400),
    ("invalid label", contact(label="no!"), 400),
    ("self", contact(account_num=ALICE["acct"], routing_num=LOCAL_ROUTING, is_external=False), 409),
])
def test_rejected_contacts_cause_no_state_change(client, alice, rows, name, body, status):
    resp = client.post("/contacts/" + ALICE["user"], json=body, headers=alice)
    assert resp.status_code == status, name
    assert not rows()


def test_duplicates_are_detected_against_persisted_contacts(client, alice, rows):
    assert client.post("/contacts/" + ALICE["user"], json=contact(), headers=alice).status_code \
        == 201
    same_account = client.post("/contacts/" + ALICE["user"], json=contact(label="Other"),
                               headers=alice)
    same_label = client.post("/contacts/" + ALICE["user"],
                             json=contact(account_num="9999999999"), headers=alice)
    assert (same_account.status_code, same_account.get_data(as_text=True)) == (
        409, "account already exists as a contact")
    assert (same_label.status_code, same_label.get_data(as_text=True)) == (
        409, "contact already exists with that label")
    assert len(rows()) == 1


def test_duplicate_check_is_scoped_to_the_user(client, alice, bob, rows):
    client.post("/contacts/" + ALICE["user"], json=contact(), headers=alice)
    assert client.post("/contacts/" + BOB["user"], json=contact(), headers=bob).status_code == 201
    assert len(rows()) == 2


def test_contact_for_user_missing_from_accounts_db_is_rejected_by_schema(
        client, keypair, rows):
    ghost = auth(make_token(keypair[0], "ghost-synth", "9000000099"))
    resp = client.post("/contacts/ghost-synth", json=contact(), headers=ghost)
    assert (resp.status_code, resp.get_data(as_text=True)) == (500, "failed to add contact")
    assert not rows()


def test_contacts_db_returns_only_requested_users_rows(postgres_url, rows):
    database = ContactsDb(postgres_url)
    database.add_contact({"username": ALICE["user"], **contact(label="A")})
    database.add_contact({"username": BOB["user"], **contact(label="B")})
    assert database.get_contacts(ALICE["user"]) == [contact(label="A")]
    assert len(rows()) == 2


def _closed_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def test_database_unavailable_returns_documented_errors(service_env, alice):
    service_env("postgresql://synthetic:synthetic@127.0.0.1:%d/postgresdb" % _closed_port())
    client = contacts_module.create_app().test_client()
    read = client.get("/contacts/" + ALICE["user"], headers=alice)
    write = client.post("/contacts/" + ALICE["user"], json=contact(), headers=alice)
    assert (read.status_code, read.get_data(as_text=True)) == (
        500, "failed to retrieve contacts list")
    assert (write.status_code, write.get_data(as_text=True)) == (500, "failed to add contact")
