"""
PostgreSQL integration fixtures.

Each test session starts a throwaway postgres:16-alpine container via
Testcontainers and applies the real accounts-db schema. The persistent demo
database is never used. Tables are emptied before every test.
"""
# pylint: disable=redefined-outer-name

import os
import pathlib
from unittest.mock import patch

import pytest
from sqlalchemy import create_engine
from testcontainers.postgres import PostgresContainer

from userservice.tests.constants import EXAMPLE_PRIVATE_KEY, EXAMPLE_PUBLIC_KEY

SCHEMA_SQL = (pathlib.Path(__file__).resolve().parents[3]
              / 'accounts-db' / 'initdb' / '0-accounts-schema.sql')


@pytest.fixture(scope='session')
def postgres_uri():
    """URI of an isolated PostgreSQL database with the accounts-db schema."""
    with PostgresContainer('postgres:16-alpine', username='userservice_test',
                           password='userservice_test', dbname='accounts_test',
                           driver='psycopg2') as container:
        uri = container.get_connection_url()
        engine = create_engine(uri)
        with engine.begin() as conn:
            conn.exec_driver_sql(SCHEMA_SQL.read_text())
        engine.dispose()
        yield uri


@pytest.fixture
def db_engine(postgres_uri):
    """Engine on the test database; tables are truncated before each test."""
    engine = create_engine(postgres_uri)
    with engine.begin() as conn:
        conn.exec_driver_sql('TRUNCATE contacts, users')
    yield engine
    engine.dispose()


@pytest.fixture
def key_files(tmp_path):
    """JWT signing key pair written to files, as mounted in production."""
    private_key = tmp_path / 'jwtRS256.key'
    public_key = tmp_path / 'jwtRS256.key.pub'
    private_key.write_bytes(EXAMPLE_PRIVATE_KEY)
    public_key.write_bytes(EXAMPLE_PUBLIC_KEY)
    return str(private_key), str(public_key)


@pytest.fixture
def make_app(key_files):
    """Build the real app (real UserDb, bcrypt, keys) against a database URI."""

    def build(db_uri, user_db_class=None):
        env = {
            'VERSION': 'integration',
            'TOKEN_EXPIRY_SECONDS': '3600',
            'PRIV_KEY_PATH': key_files[0],
            'PUB_KEY_PATH': key_files[1],
            'ENABLE_TRACING': 'false',
            'ACCOUNTS_DB_URI': db_uri,
        }
        with patch.dict(os.environ, env):
            if user_db_class is None:
                from userservice.userservice import create_app  # pylint: disable=import-outside-toplevel
                app = create_app()
            else:
                with patch('userservice.userservice.UserDb', user_db_class):
                    from userservice.userservice import create_app  # pylint: disable=import-outside-toplevel
                    app = create_app()
        app.config['TESTING'] = True
        return app

    return build


@pytest.fixture
def app(make_app, postgres_uri, db_engine):  # pylint: disable=unused-argument
    """Real userservice app wired to the isolated PostgreSQL database."""
    return make_app(postgres_uri)


@pytest.fixture
def client(app):
    """Flask test client for the real app."""
    return app.test_client()
