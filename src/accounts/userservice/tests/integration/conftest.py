# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Integration fixtures: a disposable PostgreSQL 16 (Testcontainers) with the production
accounts-db schema, and the real userservice app (real UserDb, real RSA keys)."""

from pathlib import Path

import pytest
import sqlalchemy
from testcontainers.postgres import PostgresContainer

from userservice.userservice import create_app
from userservice.tests.constants import generate_rsa_key

SCHEMA = Path(__file__).resolve().parents[3] / 'accounts-db' / 'initdb' / '0-accounts-schema.sql'
EXPIRY_SECONDS = 1800


@pytest.fixture(name='postgres_url', scope='session')
def fixture_postgres_url():
    """One throwaway database per test session; never the demo accounts-db volume."""
    with PostgresContainer('postgres:16-alpine', username='test', password='test',
                           dbname='accounts', driver='psycopg2') as container:
        url = container.get_connection_url()
        engine = sqlalchemy.create_engine(url)
        with engine.begin() as conn:
            conn.exec_driver_sql(SCHEMA.read_text())
        engine.dispose()
        yield url


@pytest.fixture(name='db')
def fixture_db(postgres_url):
    """Direct SQL access for arranging and asserting persisted state; tables emptied per test."""
    engine = sqlalchemy.create_engine(postgres_url)
    with engine.begin() as conn:
        conn.exec_driver_sql('TRUNCATE contacts, users')
    yield engine
    engine.dispose()


@pytest.fixture(name='keys', scope='session')
def fixture_keys(tmp_path_factory):
    private_key, public_key = generate_rsa_key()
    folder = tmp_path_factory.mktemp('keys')
    (folder / 'privatekey').write_bytes(private_key)
    (folder / 'publickey').write_bytes(public_key)
    return folder, public_key


def make_app(monkeypatch, keys, db_uri):
    folder, _ = keys
    for name, value in {'VERSION': 'it', 'TOKEN_EXPIRY_SECONDS': str(EXPIRY_SECONDS),
                        'PRIV_KEY_PATH': str(folder / 'privatekey'), 'PUB_KEY_PATH': str(folder / 'publickey'),
                        'ENABLE_TRACING': 'false', 'ACCOUNTS_DB_URI': db_uri}.items():
        monkeypatch.setenv(name, value)
    app = create_app()
    app.config['TESTING'] = True
    app.config['PROPAGATE_EXCEPTIONS'] = False
    return app


@pytest.fixture(name='client')
def fixture_client(monkeypatch, keys, postgres_url, db):  # pylint: disable=unused-argument
    return make_app(monkeypatch, keys, postgres_url).test_client()


def users(engine, **where):
    """All persisted users (optionally filtered by column equality) as dicts."""
    clause = ' AND '.join(f'{k} = :{k}' for k in where)
    sql = 'SELECT * FROM users' + (f' WHERE {clause}' if clause else '') + ' ORDER BY username'
    with engine.connect() as conn:
        return [dict(row._mapping) for row in conn.execute(sqlalchemy.text(sql), where)]  # pylint: disable=protected-access
