"""Real PostgreSQL boundary for contacts integration tests.

Starts a throwaway postgres:16-alpine container (Testcontainers) and applies the
production accounts-db schema. Never touches the Compose demo volumes.
"""
# pylint: disable=redefined-outer-name,missing-function-docstring

from pathlib import Path

import pytest
import sqlalchemy
from testcontainers.community.postgres import PostgresContainer

import contacts as contacts_module
from tests.helpers import ALICE, BOB

SCHEMA = Path(__file__).resolve().parents[3] / "accounts-db" / "initdb" / "0-accounts-schema.sql"
SYNTHETIC_USERS = [ALICE, BOB]


@pytest.fixture(scope="session")
def postgres_url():
    with PostgresContainer("postgres:16-alpine", driver="psycopg2") as container:
        url = container.get_connection_url()
        engine = sqlalchemy.create_engine(url)
        with engine.begin() as conn:
            conn.exec_driver_sql(SCHEMA.read_text())
        engine.dispose()
        yield url


@pytest.fixture
def pg(postgres_url):
    """Engine on a clean database seeded with synthetic users only."""
    engine = sqlalchemy.create_engine(postgres_url)
    with engine.begin() as conn:
        conn.exec_driver_sql("TRUNCATE contacts, users")
        for user in SYNTHETIC_USERS:
            conn.execute(
                sqlalchemy.text(
                    "INSERT INTO users VALUES (:acct, :user, '\\x00', 'Synthetic', 'User',"
                    " '2000-01-01', '-5', '1 Test St', 'NY', '10004', '000-00-0000')"),
                {"acct": user["acct"], "user": user["user"]},
            )
    yield engine
    engine.dispose()


@pytest.fixture
def client(pg, postgres_url, service_env):  # pylint: disable=unused-argument
    """Flask test client wired to the real database (no mocks)."""
    service_env(postgres_url)
    return contacts_module.create_app().test_client()


@pytest.fixture
def rows(pg):
    """Read persisted contacts directly from PostgreSQL, bypassing the service."""
    def read(username=None):
        return contact_rows(pg, username)
    return read


def contact_rows(engine, username=None):
    """All contact rows (optionally for one user), ordered by label."""
    query = "SELECT username, label, account_num, routing_num, is_external FROM contacts"
    params = {}
    if username:
        query += " WHERE username = :u"
        params["u"] = username
    with engine.connect() as conn:
        result = conn.execute(sqlalchemy.text(query + " ORDER BY label"), params)
        return [dict(r._mapping) for r in result]  # pylint: disable=protected-access
