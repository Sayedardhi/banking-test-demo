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

"""UserDb against the production PostgreSQL schema (accounts-db/initdb)."""

from datetime import date
from unittest.mock import patch

import bcrypt
import pytest
from sqlalchemy.exc import DataError, IntegrityError

from userservice.db import UserDb
from userservice.tests.integration.conftest import users


def record(accountid='1000000001', username='bob_1', **overrides):
    return {'accountid': accountid, 'username': username,
            'passhash': bcrypt.hashpw(b'pw', bcrypt.gensalt(rounds=4)), 'firstname': 'Bob',
            'lastname': 'Synthetic', 'birthday': '1985-07-04', 'timezone': '+1', 'address': '2 Test Rd',
            'state': 'CA', 'zip': '94043', 'ssn': '900-00-0002', **overrides}


@pytest.fixture(name='user_db')
def fixture_user_db(postgres_url, db):  # pylint: disable=unused-argument
    user_db = UserDb(postgres_url)
    yield user_db
    user_db.engine.dispose()


def test_add_then_get_round_trips_every_column(user_db):
    user = record()
    user_db.add_user(user)
    stored = user_db.get_user('bob_1')
    assert stored['birthday'] == date(1985, 7, 4)
    assert bytes(stored['passhash']) == user['passhash']
    assert bcrypt.checkpw(b'pw', stored['passhash'])
    assert {k: v for k, v in stored.items() if k not in ('birthday', 'passhash')} == {
        k: v for k, v in user.items() if k not in ('birthday', 'passhash')}


def test_get_unknown_user_is_none(user_db):
    assert user_db.get_user('ghost') is None


def test_username_lookup_is_exact(user_db):
    user_db.add_user(record())
    assert user_db.get_user('bob_') is None
    assert user_db.get_user('bob_1%') is None
    assert user_db.get_user("bob_1' OR '1'='1") is None


def test_unique_username_is_enforced_by_the_database(user_db, db):
    user_db.add_user(record())
    with pytest.raises(IntegrityError):
        user_db.add_user(record(accountid='1000000002'))
    assert len(users(db)) == 1


def test_unique_account_id_is_enforced_by_the_database(user_db, db):
    user_db.add_user(record())
    with pytest.raises(IntegrityError):
        user_db.add_user(record(username='other_1'))
    assert len(users(db)) == 1


def test_schema_rejects_values_longer_than_columns(user_db, db):
    with pytest.raises(DataError):
        user_db.add_user(record(state='CAL'))
    assert users(db) == []


def test_generated_account_id_skips_ids_already_in_postgres(user_db):
    user_db.add_user(record(accountid='1000000001'))
    with patch('random.randint', side_effect=[1000000001, 1000000001, 2000000002]) as rand:
        assert user_db.generate_accountid() == '2000000002'
    assert rand.call_count == 3
    assert rand.call_args.args == (1_000_000_000, 9_999_999_999)
