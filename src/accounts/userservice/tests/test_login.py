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

"""Unit tests for GET /login: credential checks, JWT issuance and claims, error mapping."""

from datetime import datetime
from unittest.mock import patch

import bcrypt
import jwt
import pytest
from sqlalchemy.exc import OperationalError, SQLAlchemyError

from userservice.tests.conftest import build_unit_app
from userservice.tests.constants import EXAMPLE_PUBLIC_KEY, EXAMPLE_USER, generate_rsa_key

PASSWORD = 'Synth3tic-Pass'
# A low bcrypt cost keeps the suite fast; checkpw reads the cost from the hash.
PASSHASH = bcrypt.hashpw(PASSWORD.encode(), bcrypt.gensalt(rounds=4))
FIXED_NOW = datetime(2026, 1, 15, 12, 0, 0)


def stored_user(**overrides):
    return {**EXAMPLE_USER, 'accountid': '1234567890', 'passhash': PASSHASH, **overrides}


def login(client, **params):
    return client.get('/login', query_string={'username': 'jdoe', 'password': PASSWORD, **params})


def decode(token, key=EXAMPLE_PUBLIC_KEY):
    return jwt.decode(token, key=key, algorithms=['RS256'], options={'verify_exp': False})


def test_valid_credentials_return_only_an_rs256_token(unit_app):
    _, client, users_db = unit_app
    users_db.get_user.return_value = stored_user()
    response = login(client)
    assert response.status_code == 200
    assert list(response.get_json()) == ['token']
    token = response.get_json()['token']
    assert jwt.get_unverified_header(token)['alg'] == 'RS256'
    users_db.get_user.assert_called_once_with('jdoe')


def test_token_claims_identify_the_account_and_contain_no_pii(unit_app):
    _, client, users_db = unit_app
    users_db.get_user.return_value = stored_user(ssn='900-00-1234', address='77 Synthetic Ave')
    claims = decode(login(client).get_json()['token'])
    assert set(claims) == {'user', 'acct', 'name', 'iat', 'exp'}
    assert claims['user'] == 'jdoe'
    assert claims['acct'] == '1234567890'
    assert claims['name'] == 'John Doe'
    for secret in ('900-00-1234', '77 Synthetic Ave', PASSWORD, 'passhash'):
        assert secret not in str(claims)


@pytest.mark.parametrize('expiry', [60, 3600, 86400])
def test_token_lifetime_comes_from_token_expiry_seconds(expiry):
    app, users_db = build_unit_app({'TOKEN_EXPIRY_SECONDS': str(expiry)})
    users_db.get_user.return_value = stored_user()
    with patch('userservice.userservice.datetime') as clock:
        clock.utcnow.return_value = FIXED_NOW
        token = login(app.test_client()).get_json()['token']
    claims = decode(token)
    issued = int((FIXED_NOW - datetime(1970, 1, 1)).total_seconds())
    assert claims['iat'] == issued
    assert claims['exp'] == issued + expiry


def test_issued_token_is_rejected_after_expiry(unit_app):
    _, client, users_db = unit_app
    users_db.get_user.return_value = stored_user()
    with patch('userservice.userservice.datetime') as clock:
        clock.utcnow.return_value = datetime(2000, 1, 1)
        token = login(client).get_json()['token']
    with pytest.raises(jwt.ExpiredSignatureError):
        jwt.decode(token, key=EXAMPLE_PUBLIC_KEY, algorithms=['RS256'])


def test_token_does_not_verify_with_another_key(unit_app):
    _, client, users_db = unit_app
    users_db.get_user.return_value = stored_user()
    token = login(client).get_json()['token']
    _, other_public = generate_rsa_key()
    with pytest.raises(jwt.InvalidSignatureError):
        decode(token, key=other_public)


@pytest.mark.parametrize('password', ['wrong', PASSWORD.lower(), PASSWORD + ' ', ' ' + PASSWORD, ''])
def test_wrong_password_is_401_without_token(unit_app, password):
    _, client, users_db = unit_app
    users_db.get_user.return_value = stored_user()
    response = login(client, password=password)
    assert response.status_code == 401
    assert response.get_data(as_text=True) == 'invalid login'


def test_unknown_user_is_404_without_token(unit_app):
    _, client, users_db = unit_app
    users_db.get_user.return_value = None
    response = login(client, username='nobody')
    assert response.status_code == 404
    assert 'token' not in response.get_data(as_text=True)


@pytest.mark.parametrize('error', [OperationalError('stmt', {}, Exception('db down')), SQLAlchemyError('boom')])
def test_database_error_is_generic_500(unit_app, error):
    _, client, users_db = unit_app
    users_db.get_user.side_effect = error
    response = login(client)
    assert response.status_code == 500
    assert response.get_data(as_text=True) == 'failed to retrieve user information'


@pytest.mark.parametrize('missing', ['username', 'password'])
def test_missing_credential_parameter_is_400(unit_app, missing):
    """Data validation: an incomplete login request is a client error, not a server crash."""
    _, client, users_db = unit_app
    users_db.get_user.return_value = stored_user()
    params = {'username': 'jdoe', 'password': PASSWORD}
    del params[missing]
    response = client.get('/login', query_string=params)
    assert response.status_code == 400


def test_login_logs_never_contain_the_password(unit_app, app_log):
    _, client, users_db = unit_app
    users_db.get_user.return_value = stored_user()
    login(client)
    login(client, password='Wrong-Secret-1')
    assert app_log.lines
    assert not [line for line in app_log.lines if PASSWORD in line or 'Wrong-Secret-1' in line]


def test_markup_in_password_is_handled_consistently_with_signup(unit_app):
    """Signup and login both sanitize; a password containing &<> must still round-trip."""
    _, client, users_db = unit_app
    raw = 'p&ss<w>rd'
    assert client.post('/users', data={
        'username': 'jdoe', 'password': raw, 'password-repeat': raw, 'firstname': 'J', 'lastname': 'D',
        'birthday': '2000-01-01', 'timezone': 'GMT', 'address': 'A', 'state': 'CA', 'zip': '94043',
        'ssn': '900-00-1234'}).status_code == 201
    users_db.get_user.return_value = stored_user(passhash=users_db.add_user.call_args.args[0]['passhash'])
    assert login(client, password=raw).status_code == 200
