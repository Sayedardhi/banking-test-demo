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

"""Unit tests for POST /users: validation, credential hashing, error mapping, log hygiene."""

import bcrypt
import pytest
from sqlalchemy.exc import IntegrityError, OperationalError, SQLAlchemyError

from userservice.tests.constants import EXAMPLE_USER, EXAMPLE_USER_REQUEST, EXPECTED_FIELDS

PASSWORD = 'Synth3tic-Pass'
SSN = '900-00-1234'


def signup_form(**overrides):
    """A valid synthetic signup form with optional overrides."""
    form = {**EXAMPLE_USER_REQUEST, 'password': PASSWORD, 'password-repeat': PASSWORD, 'ssn': SSN}
    form.update(overrides)
    return form


def assert_rejected(response, status, message, users_db):
    """The request was refused with the given status/message and nothing was persisted."""
    assert response.status_code == status
    assert response.get_data(as_text=True) == message
    users_db.add_user.assert_not_called()


@pytest.mark.parametrize('field', EXPECTED_FIELDS)
def test_missing_required_field_is_400_and_creates_nothing(unit_app, field):
    _, client, users_db = unit_app
    form = signup_form()
    del form[field]
    response = client.post('/users', data=form)
    assert_rejected(response, 400, 'missing required field(s)', users_db)
    users_db.generate_accountid.assert_not_called()


@pytest.mark.parametrize('field', EXPECTED_FIELDS)
def test_empty_required_field_is_400_and_creates_nothing(unit_app, field):
    _, client, users_db = unit_app
    response = client.post('/users', data=signup_form(**{field: ''}))
    assert_rejected(response, 400, 'missing value for input field(s)', users_db)


@pytest.mark.parametrize('field', ['firstname', 'ssn'])
def test_whitespace_only_required_field_is_400_and_creates_nothing(unit_app, field):
    """A blank-looking name or SSN must be treated as missing, not stored."""
    _, client, users_db = unit_app
    response = client.post('/users', data=signup_form(**{field: '   '}))
    assert_rejected(response, 400, 'missing value for input field(s)', users_db)


@pytest.mark.parametrize('username', [
    ' ', 'b', ' user', 'user ', '*$&%($', 'user*new', 'user-name', 'user.name',
    "jdoe'--", '🏦💸', 'user1💸', 'a' * 16, 'a' * 100, 'jdoe\n',
])
def test_invalid_username_is_400_and_creates_nothing(unit_app, username):
    _, client, users_db = unit_app
    response = client.post('/users', data=signup_form(username=username))
    assert response.status_code == 400
    users_db.add_user.assert_not_called()
    users_db.get_user.assert_not_called()


@pytest.mark.parametrize('username', ['ab', 'a' * 15, 'John_Doe_99', '__', '0123456789'])
def test_username_boundaries_are_accepted(unit_app, username):
    _, client, users_db = unit_app
    response = client.post('/users', data=signup_form(username=username))
    assert response.status_code == 201
    assert users_db.add_user.call_args.args[0]['username'] == username


@pytest.mark.parametrize('repeat', [PASSWORD + ' ', PASSWORD.lower(), PASSWORD[:-1], 'x'])
def test_password_mismatch_is_400_and_creates_nothing(unit_app, repeat):
    _, client, users_db = unit_app
    response = client.post('/users', data=signup_form(**{'password-repeat': repeat}))
    assert_rejected(response, 400, 'passwords do not match', users_db)


def test_existing_username_is_409_and_does_not_overwrite(unit_app):
    _, client, users_db = unit_app
    users_db.get_user.return_value = dict(EXAMPLE_USER)
    response = client.post('/users', data=signup_form())
    assert_rejected(response, 409, 'user jdoe already exists', users_db)
    users_db.get_user.assert_called_once_with('jdoe')
    users_db.generate_accountid.assert_not_called()


def test_password_is_stored_only_as_salted_bcrypt_hash(unit_app):
    _, client, users_db = unit_app
    assert client.post('/users', data=signup_form()).status_code == 201
    record = users_db.add_user.call_args.args[0]
    assert 'password' not in record and 'password-repeat' not in record
    assert record['passhash'].startswith(b'$2b$')
    assert PASSWORD.encode() not in record['passhash']
    assert bcrypt.checkpw(PASSWORD.encode(), record['passhash'])
    assert not bcrypt.checkpw(b'wrong', record['passhash'])


def test_same_password_gets_a_different_salt_per_user(unit_app):
    _, client, users_db = unit_app
    client.post('/users', data=signup_form(username='alice'))
    client.post('/users', data=signup_form(username='bob'))
    first, second = (c.args[0]['passhash'] for c in users_db.add_user.call_args_list)
    assert first != second


def test_non_ascii_password_is_hashed_as_utf8(unit_app):
    _, client, users_db = unit_app
    secret = 'pässwörd-日本'
    response = client.post('/users', data=signup_form(password=secret, **{'password-repeat': secret}))
    assert response.status_code == 201
    assert bcrypt.checkpw(secret.encode('utf-8'), users_db.add_user.call_args.args[0]['passhash'])


def test_record_uses_generated_account_id_and_submitted_profile(unit_app):
    _, client, users_db = unit_app
    response = client.post('/users', data=signup_form())
    assert response.status_code == 201
    assert response.get_json() == {}
    record = users_db.add_user.call_args.args[0]
    assert record['accountid'] == '1234567890'
    assert {k: record[k] for k in ('firstname', 'lastname', 'birthday', 'state', 'zip', 'ssn')} == {
        'firstname': 'John', 'lastname': 'Doe', 'birthday': '2000-01-01',
        'state': 'CA', 'zip': '94043', 'ssn': SSN}


def test_markup_in_profile_fields_is_escaped_before_storage(unit_app):
    _, client, users_db = unit_app
    response = client.post('/users', data=signup_form(firstname='<script>alert(1)</script>',
                                                      address='1 Main St <b onclick=x>'))
    assert response.status_code == 201
    record = users_db.add_user.call_args.args[0]
    assert '<script>' not in record['firstname'] and '&lt;script&gt;' in record['firstname']
    assert 'onclick' not in record['address']


def test_unexpected_extra_fields_are_not_persisted(unit_app):
    _, client, users_db = unit_app
    response = client.post('/users', data=signup_form(accountid='0000000001', passhash='x', admin='1'))
    assert response.status_code == 201
    record = users_db.add_user.call_args.args[0]
    assert record['accountid'] == '1234567890'
    assert 'admin' not in record and record['passhash'] != b'x'


@pytest.mark.parametrize('failing_call', ['get_user', 'generate_accountid', 'add_user'])
@pytest.mark.parametrize('error', [OperationalError('stmt', {}, Exception('db down')),
                                   IntegrityError('stmt', {}, Exception('dup')),
                                   SQLAlchemyError('boom')])
def test_database_errors_return_generic_500(unit_app, failing_call, error):
    _, client, users_db = unit_app
    getattr(users_db, failing_call).side_effect = error
    response = client.post('/users', data=signup_form())
    assert response.status_code == 500
    assert response.get_data(as_text=True) == 'failed to create user'


def test_signup_debug_logs_never_contain_password_or_ssn(unit_app, app_log):
    """Credential handling: even at DEBUG, plaintext passwords and SSNs must not be logged."""
    _, client, _ = unit_app
    assert client.post('/users', data=signup_form()).status_code == 201
    leaked = [line for line in app_log.lines if PASSWORD in line or SSN in line]
    assert not leaked, f'{len(leaked)} log line(s) contain the password or SSN'


def test_rejected_signup_logs_reason_without_password(unit_app, app_log):
    _, client, _ = unit_app
    app_log.lines.clear()
    client.post('/users', data=signup_form(**{'password-repeat': 'other'}))
    errors = [line for line in app_log.lines if line.startswith('Error creating new user')]
    assert errors == ['Error creating new user: passwords do not match']
