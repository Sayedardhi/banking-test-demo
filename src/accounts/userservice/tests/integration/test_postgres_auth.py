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

"""HTTP API -> PostgreSQL integration: signup persistence, login/JWT, rejected writes, outages."""

import threading
from datetime import date
from unittest.mock import patch

import bcrypt
import jwt
import pytest

from userservice.db import UserDb as RealUserDb
from userservice.tests.integration.conftest import EXPIRY_SECONDS, make_app, users

PASSWORD = 'Synth3tic-Pass'


def form(username='alice_1', **overrides):
    return {'username': username, 'password': PASSWORD, 'password-repeat': PASSWORD,
            'firstname': 'Alice', 'lastname': 'Synthetic', 'birthday': '1990-02-03', 'timezone': '-5',
            'address': '1 Test Way', 'state': 'NY', 'zip': '10001', 'ssn': '900-00-0001', **overrides}


def test_signup_persists_one_complete_user_with_hashed_password(client, db):
    response = client.post('/users', data=form())
    assert response.status_code == 201
    [row] = users(db)
    assert row['username'] == 'alice_1'
    assert len(row['accountid']) == 10 and row['accountid'].isdigit()
    assert row['birthday'] == date(1990, 2, 3)
    assert (row['firstname'], row['lastname'], row['state'], row['zip']) == ('Alice', 'Synthetic', 'NY', '10001')
    assert row['ssn'].strip() == '900-00-0001'
    passhash = bytes(row['passhash'])
    assert PASSWORD.encode() not in passhash
    assert bcrypt.checkpw(PASSWORD.encode(), passhash)


def test_signed_up_user_logs_in_and_token_matches_the_database_row(client, db, keys):
    client.post('/users', data=form())
    [row] = users(db)
    response = client.get('/login', query_string={'username': 'alice_1', 'password': PASSWORD})
    assert response.status_code == 200
    claims = jwt.decode(response.get_json()['token'], key=keys[1], algorithms=['RS256'])
    assert claims['acct'] == row['accountid']
    assert claims['user'] == 'alice_1'
    assert claims['name'] == 'Alice Synthetic'
    assert claims['exp'] - claims['iat'] == EXPIRY_SECONDS


def test_each_signup_gets_a_distinct_account_id(client, db):
    for i in range(5):
        assert client.post('/users', data=form(f'user_{i}')).status_code == 201
    ids = [row['accountid'] for row in users(db)]
    assert len(set(ids)) == 5


@pytest.mark.parametrize('params, status', [
    ({'username': 'alice_1', 'password': 'wrong'}, 401),
    ({'username': 'alice_1', 'password': PASSWORD.upper()}, 401),
    ({'username': 'ALICE_1', 'password': PASSWORD}, 404),
    ({'username': 'nobody', 'password': PASSWORD}, 404),
    ({'username': "alice_1' OR '1'='1", 'password': PASSWORD}, 404),
    ({'username': "' OR 1=1 --", 'password': "' OR 1=1 --"}, 404),
])
def test_bad_credentials_never_issue_a_token(client, params, status):
    client.post('/users', data=form())
    response = client.get('/login', query_string=params)
    assert response.status_code == status
    assert 'token' not in response.get_data(as_text=True)


def test_duplicate_username_is_409_and_original_account_is_unchanged(client, db):
    client.post('/users', data=form())
    [before] = users(db)
    response = client.post('/users', data=form(firstname='Mallory', password='Other-Pass1',
                                               **{'password-repeat': 'Other-Pass1'}))
    assert response.status_code == 409
    [after] = users(db)
    assert after == before
    assert client.get('/login', query_string={'username': 'alice_1', 'password': 'Other-Pass1'}).status_code == 401


@pytest.mark.parametrize('overrides', [
    {'password-repeat': 'mismatch'}, {'username': 'a'}, {'username': 'bad name'}, {'ssn': ''}, {'zip': ''},
])
def test_rejected_signup_writes_nothing(client, db, overrides):
    response = client.post('/users', data=form(**overrides))
    assert response.status_code == 400
    assert users(db) == []


def test_oversized_profile_field_is_rejected_as_invalid_input(client, db):
    """The schema allows a 5-character zip; a 6-character zip is invalid client input (400)."""
    response = client.post('/users', data=form(zip='100011'))
    assert response.status_code == 400
    assert users(db) == []


def test_markup_is_stored_escaped(client, db):
    client.post('/users', data=form(lastname='<script>x</script>'))
    [row] = users(db)
    assert '<script>' not in row['lastname']


def test_password_with_markup_characters_round_trips_through_postgres(client):
    raw = 'p&ss<w>rd"1'
    assert client.post('/users', data=form(password=raw, **{'password-repeat': raw})).status_code == 201
    assert client.get('/login', query_string={'username': 'alice_1', 'password': raw}).status_code == 200
    assert client.get('/login', query_string={'username': 'alice_1', 'password': 'p&amp;ss'}).status_code == 401


def test_concurrent_signups_for_one_username_create_one_account_and_report_conflict(
        monkeypatch, keys, postgres_url, db):
    """Two simultaneous signups both pass the existence check (forced by a barrier after the real
    SELECT); exactly one row may be created and the loser must get the documented 409."""
    barrier = threading.Barrier(2, timeout=10)

    class RacingUserDb(RealUserDb):
        def get_user(self, username):
            result = super().get_user(username)
            barrier.wait()
            return result

    with patch('userservice.userservice.UserDb', RacingUserDb):
        app = make_app(monkeypatch, keys, postgres_url)
    statuses = []

    def submit(first_name):
        statuses.append(app.test_client().post('/users', data=form(firstname=first_name)).status_code)

    threads = [threading.Thread(target=submit, args=(name,)) for name in ('First', 'Second')]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(30)
    assert len(users(db)) == 1
    assert sorted(statuses) == [201, 409]


def test_database_outage_returns_documented_500s(monkeypatch, keys):
    """Controlled substitute: ACCOUNTS_DB_URI points at a closed port (connection refused)."""
    client = make_app(monkeypatch, keys, 'postgresql://test:test@127.0.0.1:9/accounts').test_client()
    signup = client.post('/users', data=form())
    assert (signup.status_code, signup.get_data(as_text=True)) == (500, 'failed to create user')
    login = client.get('/login', query_string={'username': 'alice_1', 'password': PASSWORD})
    assert (login.status_code, login.get_data(as_text=True)) == (500, 'failed to retrieve user information')


def test_database_error_logs_do_not_expose_ssn_or_password_hash(client, caplog):
    """A real PostgreSQL error during signup is logged at ERROR (default level); the log line
    must not carry the applicant's SSN or password hash."""
    caplog.set_level('ERROR', logger='userservice.userservice')
    response = client.post('/users', data=form(zip='100011'))
    assert response.status_code in (400, 500)
    errors = [r.getMessage() for r in caplog.records if r.levelname == 'ERROR']
    assert errors, 'expected the failure to be logged'
    assert not [line for line in errors if '900-00-0001' in line or 'passhash' in line]
