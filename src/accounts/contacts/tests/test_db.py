"""Unit tests for ContactsDb row mapping and filtering (in-memory SQLite).

PostgreSQL behaviour (schema, constraints, real driver) is covered by
tests/integration.
"""
# pylint: disable=redefined-outer-name,missing-function-docstring

import logging

import pytest
from sqlalchemy.exc import SQLAlchemyError

from db import ContactsDb
from tests.helpers import contact


@pytest.fixture
def contacts_db():
    database = ContactsDb("sqlite:///:memory:")
    database.contacts_table.create(database.engine)
    return database


def _row(username, **overrides):
    return {"username": username, **contact(**overrides)}


def test_get_contacts_for_user_without_contacts_is_empty(contacts_db):
    assert contacts_db.get_contacts("nobody") == []


def test_add_then_get_returns_api_shape_without_username(contacts_db):
    contacts_db.add_contact(_row("alice", label="Rent", account_num="1111111111"))
    assert contacts_db.get_contacts("alice") == [{
        "label": "Rent", "account_num": "1111111111",
        "routing_num": "111000025", "is_external": True,
    }]


def test_get_contacts_only_returns_requested_users_rows(contacts_db):
    contacts_db.add_contact(_row("alice", label="A1", account_num="1111111111"))
    contacts_db.add_contact(_row("alice", label="A2", account_num="2222222222"))
    contacts_db.add_contact(_row("bob", label="B1", account_num="3333333333"))
    assert sorted(c["label"] for c in contacts_db.get_contacts("alice")) == ["A1", "A2"]
    assert [c["label"] for c in contacts_db.get_contacts("bob")] == ["B1"]


def test_add_contact_missing_required_column_raises_sqlalchemy_error(contacts_db):
    row = _row("alice")
    del row["routing_num"]
    with pytest.raises(SQLAlchemyError):
        contacts_db.add_contact(row)
    assert contacts_db.get_contacts("alice") == []


def test_query_logging_does_not_include_bound_account_numbers(contacts_db, caplog):
    caplog.set_level(logging.DEBUG)
    contacts_db.logger = logging.getLogger("contacts-db-test")
    contacts_db.add_contact(_row("alice", account_num="4815162342"))
    contacts_db.get_contacts("alice")
    assert "QUERY" in caplog.text
    assert "4815162342" not in caplog.text
