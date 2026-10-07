"""Integration tests: contacts API -> ContactsDb -> real PostgreSQL (accounts-db schema).

Nothing below the HTTP handler is mocked. Assertions check rows in the database, so a rejected
request is proven to leave no partial state behind.
"""
import logging

import pytest
import sqlalchemy

import contacts
from db import ContactsDb
from support import ALICE, BOB, EXTERNAL_ROUTING, LOCAL_ROUTING, new_contact

ROWS = "SELECT username, label, account_num, routing_num, is_external FROM contacts ORDER BY label"


def post(client, headers, body, user=ALICE["user"]):
    return client.post(f"/contacts/{user}", headers=headers, json=body)


def test_added_contact_is_persisted_exactly_and_returned(client, auth, sql):
    body = new_contact(label="a" * 30, account_num="0000000001")
    assert post(client, auth(), body).status_code == 201

    assert sql(ROWS) == [{"username": "alice", "label": "a" * 30, "account_num": "0000000001",
                          "routing_num": EXTERNAL_ROUTING, "is_external": True}]
    res = client.get("/contacts/alice", headers=auth())
    assert (res.status_code, res.get_json()) == (200, [new_contact(label="a" * 30, account_num="0000000001")])


def test_contacts_are_isolated_per_user(client, auth, sql):
    assert post(client, auth(ALICE), new_contact(label="Alice Landlord")).status_code == 201
    # Same external account saved by another customer is a separate contact, not a duplicate.
    assert post(client, auth(BOB), new_contact(label="Bob Landlord"), user="bob").status_code == 201

    assert [c["label"] for c in client.get("/contacts/bob", headers=auth(BOB)).get_json()] == ["Bob Landlord"]
    assert client.get("/contacts/alice", headers=auth(BOB)).status_code == 401
    assert len(sql(ROWS)) == 2


def test_username_with_sql_metacharacters_is_a_literal_value(client, make_token, sql):
    assert post(client, auth_for(make_token, ALICE), new_contact()).status_code == 201
    attacker = {"user": "alice' OR '1'='1", "acct": "1055757655"}
    res = client.get(f"/contacts/{attacker['user']}", headers=auth_for(make_token, attacker))
    assert (res.status_code, res.get_json()) == (200, [])
    assert len(sql(ROWS)) == 1


def auth_for(make_token, identity):
    return {"Authorization": "Bearer " + make_token(identity)}


@pytest.mark.parametrize("body,status,message", [
    (new_contact(account_num="123456789"), 400, "invalid account number"),
    (new_contact(routing_num="12345678"), 400, "invalid routing number"),
    (new_contact(routing_num=LOCAL_ROUTING), 400, "invalid routing number"),
    (new_contact(label="<img src=x onerror=alert(1)>"), 400, "invalid account label"),
    (new_contact(account_num=ALICE["acct"], routing_num=LOCAL_ROUTING, is_external=False), 409,
     "may not add yourself to contacts"),
])
def test_rejected_contact_writes_nothing(client, auth, sql, body, status, message):
    res = post(client, auth(), body)
    assert (res.status_code, res.text) == (status, message)
    assert not sql(ROWS)


def test_duplicates_are_detected_against_persisted_rows(client, auth, sql):
    assert post(client, auth(), new_contact(label="Original")).status_code == 201

    same_account = post(client, auth(), new_contact(label="Renamed"))
    same_label = post(client, auth(), new_contact(label="Original", account_num="1234567890"))

    assert (same_account.status_code, same_account.text) == (409, "account already exists as a contact")
    assert (same_label.status_code, same_label.text) == (409, "contact already exists with that label")
    assert [r["label"] for r in sql(ROWS)] == ["Original"]


def test_contact_for_unknown_user_is_rejected_without_a_row(client, make_token, sql):
    """The token's user has no users row (FK violation): the insert fails and nothing is stored."""
    res = post(client, auth_for(make_token, {"user": "ghost", "acct": "1999999999"}), new_contact(), user="ghost")
    assert (res.status_code, res.text) == (500, "failed to add contact")
    assert not sql(ROWS)


def test_database_error_log_does_not_contain_the_account_number(client, make_token, caplog):
    """A real PostgreSQL error at the production log level must not leak the full account number."""
    caplog.set_level(logging.INFO)
    body = new_contact(account_num="9087654321")
    assert post(client, auth_for(make_token, {"user": "ghost", "acct": "1999999999"}), body,
                user="ghost").status_code == 500
    logged = "\n".join(r.getMessage() for r in caplog.records if r.levelno >= logging.INFO)
    assert "foreign key" in logged
    assert body["account_num"] not in logged


def test_database_unavailable_returns_documented_errors(service_env, auth):
    """Engine creation is lazy, so the service starts; requests then fail with 500, not a crash."""
    service_env.setenv("ACCOUNTS_DB_URI", "postgresql://nobody:nothing@127.0.0.1:1/none")
    client = contacts.create_app().test_client()
    get = client.get("/contacts/alice", headers=auth())
    add = post(client, auth(), new_contact())
    assert (get.status_code, get.text) == (500, "failed to retrieve contacts list")
    assert (add.status_code, add.text) == (500, "failed to add contact")


def test_contacts_db_round_trip_returns_only_contact_fields(database_url, sql):
    db = ContactsDb(database_url)
    db.add_contact({"username": "bob", "label": "Payroll", "account_num": "1044226144",
                    "routing_num": LOCAL_ROUTING, "is_external": False})

    assert db.get_contacts("bob") == [{"label": "Payroll", "account_num": "1044226144",
                                       "routing_num": LOCAL_ROUTING, "is_external": False}]
    assert not db.get_contacts("alice")
    assert len(sql(ROWS)) == 1


def test_contacts_db_raises_sqlalchemy_error_on_constraint_violation(database_url, sql):
    db = ContactsDb(database_url)
    with pytest.raises(sqlalchemy.exc.IntegrityError):
        db.add_contact({"username": "bob", "label": None, "account_num": "1044226144",
                        "routing_num": LOCAL_ROUTING, "is_external": False})
    assert not sql(ROWS)
