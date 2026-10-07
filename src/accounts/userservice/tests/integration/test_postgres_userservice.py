"""
Integration tests: userservice HTTP API -> real UserDb -> real PostgreSQL
using the accounts-db schema. Covers persisted state, rejected operations,
duplicate users, JWT issuance from stored credentials, database constraint
and availability failures, and sensitive-data handling.
"""
# pylint: disable=redefined-outer-name

import logging
import threading
from datetime import date
from unittest.mock import patch

import bcrypt
import jwt
import pytest
from sqlalchemy import text

from db import UserDb
from userservice.tests.app_factory import VALID_SIGNUP, SYNTHETIC_PASSWORD, SYNTHETIC_SSN
from userservice.tests.constants import EXAMPLE_PUBLIC_KEY


def rows(engine, username=None):
    """All users rows (optionally for one username) as dicts."""
    query = 'SELECT * FROM users'
    params = {}
    if username is not None:
        query += ' WHERE username = :username'
        params['username'] = username
    with engine.connect() as conn:
        return [dict(r._mapping) for r in conn.execute(text(query), params)]  # pylint: disable=protected-access


def signup(client, **overrides):
    """POST /users with field overrides (None removes a field)."""
    form = dict(VALID_SIGNUP)
    for key, value in overrides.items():
        if value is None:
            form.pop(key, None)
        else:
            form[key] = value
    return client.post('/users', data=form)


def login(client, username='alice_01', password=SYNTHETIC_PASSWORD):
    """GET /login."""
    return client.get('/login', query_string={'username': username, 'password': password})


def test_signup_persists_one_row_with_bcrypt_hash_and_no_plaintext_password(client, db_engine):
    """the stored record matches the request; the password is only a hash"""
    assert signup(client).status_code == 201
    stored = rows(db_engine)
    assert len(stored) == 1
    row = stored[0]
    passhash = bytes(row['passhash'])
    assert row['accountid'].isdigit() and len(row['accountid']) == 10
    assert passhash.startswith(b'$2b$')
    assert bcrypt.checkpw(SYNTHETIC_PASSWORD.encode(), passhash)
    assert SYNTHETIC_PASSWORD not in repr(row)
    assert row['birthday'] == date(1990, 4, 15)
    assert (row['username'], row['firstname'], row['lastname'], row['state'], row['zip'],
            row['ssn']) == ('alice_01', 'Alice', 'Tester', 'NY', '10001', SYNTHETIC_SSN)


def test_signup_then_login_issues_jwt_bound_to_persisted_account(client, db_engine):
    """login reads the stored bcrypt hash and signs the stored account id"""
    assert signup(client).status_code == 201
    response = login(client)
    assert response.status_code == 200
    claims = jwt.decode(response.get_json()['token'], key=EXAMPLE_PUBLIC_KEY,
                        algorithms=['RS256'])
    assert claims['user'] == 'alice_01'
    assert claims['acct'] == rows(db_engine)[0]['accountid']
    assert claims['name'] == 'Alice Tester'
    assert claims['exp'] - claims['iat'] == 3600


def test_duplicate_signup_returns_409_and_leaves_original_row_unchanged(client, db_engine):
    """re-registering a username cannot overwrite the original credentials"""
    assert signup(client).status_code == 201
    original = rows(db_engine)[0]
    response = signup(client, password='Other-Pass-1', ssn='000-00-0002',
                      **{'password-repeat': 'Other-Pass-1'})
    assert response.status_code == 409
    assert response.get_data(as_text=True) == 'user alice_01 already exists'
    assert rows(db_engine) == [original]
    assert login(client).status_code == 200
    assert login(client, password='Other-Pass-1').status_code == 401


@pytest.mark.parametrize('overrides, message', [
    ({'password-repeat': 'mismatch'}, 'passwords do not match'),
    ({'username': 'bad name!'},
     'username must contain 2-15 alphanumeric characters or underscores'),
    ({'ssn': None}, 'missing required field(s)'),
    ({'lastname': ''}, 'missing value for input field(s)'),
], ids=['password-mismatch', 'invalid-username', 'missing-ssn', 'empty-lastname'])
def test_rejected_signup_writes_no_row(client, db_engine, overrides, message):
    """validation failures leave the database untouched"""
    response = signup(client, **overrides)
    assert response.status_code == 400
    assert response.get_data(as_text=True) == message
    assert rows(db_engine) == []


def test_wrong_password_returns_401_without_token(client):
    """stored hash rejects a wrong password"""
    assert signup(client).status_code == 201
    response = login(client, password='Wrong-Guess-42')
    assert response.status_code == 401
    assert 'token' not in response.get_data(as_text=True)


def test_unknown_user_returns_404_without_token(client):
    """login for a username that was never stored fails"""
    response = login(client, username='ghost_user')
    assert response.status_code == 404
    assert 'token' not in response.get_data(as_text=True)


@pytest.mark.parametrize('field, value', [
    ('zip', '100011'), ('state', 'NYC'), ('timezone', 'UTC+05:30'),
], ids=['zip-too-long', 'state-too-long', 'timezone-too-long'])
def test_schema_constraint_violation_returns_controlled_500_and_no_row(
        client, db_engine, field, value):
    """database rejection is reported generically with no partial write"""
    response = signup(client, **{field: value})
    assert response.status_code == 500
    body = response.get_data(as_text=True)
    assert body == 'failed to create user'
    assert SYNTHETIC_SSN not in body
    assert rows(db_engine) == []


def test_concurrent_duplicate_signups_create_exactly_one_account(make_app, postgres_uri,
                                                                db_engine):
    """the database unique constraint protects against a check-then-insert race"""
    barrier = threading.Barrier(2, timeout=10)

    class RacingUserDb(UserDb):
        """Real UserDb that holds both requests after the existence check."""

        def get_user(self, username):
            """Real lookup, then wait until the other request has also looked up."""
            user = super().get_user(username)
            barrier.wait()
            return user

    app = make_app(postgres_uri, RacingUserDb)
    statuses = []

    def register():
        """One signup request on its own client."""
        statuses.append(signup(app.test_client()).status_code)

    threads = [threading.Thread(target=register) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=30)
    assert sorted(statuses)[0] == 201 and statuses.count(201) == 1
    assert max(statuses) >= 400
    assert len(rows(db_engine, 'alice_01')) == 1


def test_accountid_generation_retries_on_existing_id(client, db_engine):
    """a colliding random account id is regenerated, never reused"""
    with patch('random.randint', side_effect=[1000000001, 1000000001, 1000000002]):
        assert signup(client, username='first_user').status_code == 201
        assert signup(client, username='second_user').status_code == 201
    ids = {r['username']: r['accountid'] for r in rows(db_engine)}
    assert ids == {'first_user': '1000000001', 'second_user': '1000000002'}


def test_generated_accountids_are_unique_ten_digit_numbers(client, db_engine):
    """account ids fit the CHAR(10) column and are distinct"""
    for i in range(5):
        assert signup(client, username='user_{}'.format(i)).status_code == 201
    ids = [r['accountid'] for r in rows(db_engine)]
    assert len(set(ids)) == 5
    assert all(i.isdigit() and len(i) == 10 for i in ids)


def test_markup_is_sanitized_before_persisting_and_in_token(client, db_engine):
    """escaped values are what is stored and what is signed"""
    assert signup(client, firstname='<script>x</script>').status_code == 201
    assert rows(db_engine)[0]['firstname'] == '&lt;script&gt;x&lt;/script&gt;'
    claims = jwt.decode(login(client).get_json()['token'], key=EXAMPLE_PUBLIC_KEY,
                        algorithms=['RS256'])
    assert claims['name'] == '&lt;script&gt;x&lt;/script&gt; Tester'


def test_password_with_markup_characters_round_trips_through_postgres(client):
    """a password containing &, < and > can log in after signup"""
    password = 'p&ss<w>rd"1'
    assert signup(client, password=password, **{'password-repeat': password}).status_code == 201
    assert login(client, password=password).status_code == 200


def test_database_unavailable_returns_500_and_no_token(make_app):
    """dependency failure is reported, never treated as success"""
    app = make_app('postgresql://nobody:nothing@127.0.0.1:1/accounts')
    client = app.test_client()
    response = signup(client)
    assert response.status_code == 500
    assert response.get_data(as_text=True) == 'failed to create user'
    response = login(client)
    assert response.status_code == 500
    assert response.get_data(as_text=True) == 'failed to retrieve user information'


class _Capture(logging.Handler):
    """Collects formatted log records."""

    def __init__(self):
        super().__init__(logging.DEBUG)
        self.lines = []

    def emit(self, record):
        self.lines.append(record.getMessage())


def capture_logs(app, level):
    """Attach a capture handler to the app logger at level."""
    handler = _Capture()
    app.logger.addHandler(handler)
    app.logger.setLevel(level)
    return handler


def test_login_flow_with_sql_debug_logging_never_logs_password_or_ssn(app, client):
    """DEBUG query/result logs for real SQL omit credentials and SSN"""
    assert signup(client).status_code == 201
    handler = capture_logs(app, logging.DEBUG)
    assert login(client).status_code == 200
    assert login(client, password='Wrong-Guess-42').status_code == 401
    text_logs = '\n'.join(handler.lines)
    assert 'QUERY' in text_logs
    for secret in (SYNTHETIC_PASSWORD, SYNTHETIC_SSN, 'Wrong-Guess-42'):
        assert secret not in text_logs


def test_signup_at_default_info_level_never_logs_password_or_ssn(app, client):
    """default production log level"""
    handler = capture_logs(app, logging.INFO)
    assert signup(client).status_code == 201
    text_logs = '\n'.join(handler.lines)
    assert 'Successfully created user.' in text_logs
    assert SYNTHETIC_PASSWORD not in text_logs
    assert SYNTHETIC_SSN not in text_logs
