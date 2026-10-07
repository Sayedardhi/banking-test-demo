"""PostgreSQL fixtures for the contacts integration tests.

One disposable postgres:16-alpine container per session (Testcontainers) with the real
accounts-db schema; contacts rows are removed after every test. The demo's accounts-db
volume is never used.
"""
# pylint: disable=redefined-outer-name
from pathlib import Path

import pytest
import sqlalchemy
from testcontainers.postgres import PostgresContainer

import contacts
from support import ALICE, BOB

SCHEMA = Path(__file__).resolve().parents[3] / "accounts-db/initdb/0-accounts-schema.sql"
USERS = [ALICE, BOB, {"user": "alice' OR '1'='1", "acct": "1055757655"}]


@pytest.fixture(scope="session")
def database_url():
    with PostgresContainer("postgres:16-alpine", driver="psycopg2") as pg:
        url = pg.get_connection_url()
        engine = sqlalchemy.create_engine(url)
        with engine.begin() as conn:
            conn.exec_driver_sql(SCHEMA.read_text())
            for i, user in enumerate(USERS):
                conn.execute(sqlalchemy.text(
                    "INSERT INTO users VALUES (:acct, :user, 'x', 'Synthetic', :last, '1990-01-01',"
                    " 'GMT', '1 Test Street', 'NY', '10001', '000-00-000' || :i)"),
                    {"acct": user["acct"], "user": user["user"], "last": f"User{i}", "i": i})
        engine.dispose()
        yield url


@pytest.fixture
def sql(database_url):
    """Direct SQL access for asserting persisted state."""
    engine = sqlalchemy.create_engine(database_url)

    def query(statement, **params):
        with engine.connect() as conn:
            return [dict(row._mapping) for row in conn.execute(sqlalchemy.text(statement), params)]
    yield query
    with engine.begin() as conn:
        conn.exec_driver_sql("DELETE FROM contacts")
    engine.dispose()


@pytest.fixture
def client(service_env, database_url, sql):  # pylint: disable=unused-argument
    service_env.setenv("ACCOUNTS_DB_URI", database_url)
    app = contacts.create_app()
    app.testing = True
    return app.test_client()
