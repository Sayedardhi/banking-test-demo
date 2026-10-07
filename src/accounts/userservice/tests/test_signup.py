"""
Unit tests for account creation (POST /users): input validation, duplicate
users, password hashing, sanitization and database error handling.
"""

import unittest

import bcrypt
from sqlalchemy.exc import SQLAlchemyError

from userservice.tests.app_factory import build_test_app, VALID_SIGNUP, SYNTHETIC_PASSWORD
from userservice.tests.constants import EXPECTED_FIELDS, INVALID_USERNAMES


class TestSignup(unittest.TestCase):
    """POST /users business rules with the database adapter mocked."""

    def setUp(self):
        self.app, self.client, self.users_db = build_test_app()
        self.users_db.get_user.return_value = None
        self.users_db.generate_accountid.return_value = '1234567890'

    def signup(self, **overrides):
        """Submit the signup form with field overrides (None removes a field)."""
        form = dict(VALID_SIGNUP)
        for key, value in overrides.items():
            if value is None:
                form.pop(key, None)
            else:
                form[key] = value
        return self.client.post('/users', data=form)

    def persisted_user(self):
        """The record handed to UserDb.add_user."""
        self.users_db.add_user.assert_called_once()
        return self.users_db.add_user.call_args[0][0]

    def assert_rejected(self, response, status, message):
        """Assert a rejection with the given status/body and no database write."""
        self.assertEqual(response.status_code, status)
        self.assertEqual(response.get_data(as_text=True), message)
        self.users_db.add_user.assert_not_called()

    def test_valid_signup_returns_201_with_empty_body(self):
        """valid request creates the user and echoes nothing back"""
        response = self.signup()
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.get_json(), {})
        self.assertEqual(self.persisted_user()['accountid'], '1234567890')

    def test_each_missing_required_field_returns_400_and_writes_nothing(self):
        """every documented field is mandatory"""
        for field in EXPECTED_FIELDS:
            with self.subTest(field=field):
                self.users_db.add_user.reset_mock()
                response = self.signup(**{field: None})
                self.assert_rejected(response, 400, 'missing required field(s)')

    def test_each_empty_required_field_returns_400_and_writes_nothing(self):
        """an empty value counts as missing"""
        for field in EXPECTED_FIELDS:
            with self.subTest(field=field):
                self.users_db.add_user.reset_mock()
                response = self.signup(**{field: ''})
                self.assert_rejected(response, 400, 'missing value for input field(s)')

    def test_whitespace_only_required_values_are_rejected(self):
        """a value of only spaces is not a real value for identity fields"""
        for field in ('firstname', 'lastname', 'address', 'ssn'):
            with self.subTest(field=field):
                self.users_db.add_user.reset_mock()
                response = self.signup(**{field: '   '})
                self.assert_rejected(response, 400, 'missing value for input field(s)')

    def test_invalid_usernames_return_400_and_write_nothing(self):
        """usernames must be 2-15 alphanumeric/underscore characters"""
        for username in [u for u in INVALID_USERNAMES if u is not None]:
            with self.subTest(username=username):
                self.users_db.add_user.reset_mock()
                response = self.signup(username=username)
                self.assertEqual(response.status_code, 400)
                self.users_db.add_user.assert_not_called()

    def test_boundary_valid_usernames_are_accepted(self):
        """2 and 15 characters, digits and underscores are allowed"""
        for username in ('ab', 'a' * 15, 'user_01', 'ABC_def9', '__'):
            with self.subTest(username=username):
                self.users_db.add_user.reset_mock()
                response = self.signup(username=username)
                self.assertEqual(response.status_code, 201)
                self.assertEqual(self.persisted_user()['username'], username)

    def test_password_mismatch_returns_400_and_writes_nothing(self):
        """password and password-repeat must match"""
        response = self.signup(**{'password-repeat': SYNTHETIC_PASSWORD + 'x'})
        self.assert_rejected(response, 400, 'passwords do not match')

    def test_password_match_is_case_sensitive(self):
        """passwords differing only in case do not match"""
        response = self.signup(password='Secret1', **{'password-repeat': 'secret1'})
        self.assert_rejected(response, 400, 'passwords do not match')

    def test_duplicate_username_returns_409_without_insert_or_new_accountid(self):
        """existing usernames cannot be re-registered"""
        self.users_db.get_user.return_value = {'username': 'alice_01'}
        response = self.signup()
        self.assert_rejected(response, 409, 'user alice_01 already exists')
        self.users_db.get_user.assert_called_once_with('alice_01')
        self.users_db.generate_accountid.assert_not_called()

    def test_password_is_stored_only_as_salted_bcrypt_hash(self):
        """plaintext password never reaches the database"""
        self.signup()
        user = self.persisted_user()
        self.assertNotIn('password', user)
        self.assertNotIn('password-repeat', user)
        self.assertIsInstance(user['passhash'], bytes)
        self.assertTrue(user['passhash'].startswith(b'$2b$'))
        self.assertNotIn(SYNTHETIC_PASSWORD.encode(), user['passhash'])
        self.assertTrue(bcrypt.checkpw(SYNTHETIC_PASSWORD.encode(), user['passhash']))
        self.assertFalse(bcrypt.checkpw(b'wrong-password', user['passhash']))

    def test_same_password_gets_a_unique_salt_per_user(self):
        """identical passwords must not produce identical hashes"""
        self.signup(username='user_one')
        first = self.users_db.add_user.call_args[0][0]['passhash']
        self.signup(username='user_two')
        second = self.users_db.add_user.call_args[0][0]['passhash']
        self.assertNotEqual(first, second)

    def test_persisted_record_has_exactly_the_schema_fields(self):
        """record matches the accounts-db users table, values unchanged"""
        self.signup()
        user = self.persisted_user()
        self.assertEqual(set(user), {
            'accountid', 'username', 'passhash', 'firstname', 'lastname', 'birthday',
            'timezone', 'address', 'state', 'zip', 'ssn'})
        for field in ('username', 'firstname', 'lastname', 'birthday', 'timezone',
                      'address', 'state', 'zip', 'ssn'):
            self.assertEqual(user[field], VALID_SIGNUP[field])

    def test_html_in_fields_is_sanitized_before_storage(self):
        """markup is escaped before it is persisted"""
        self.signup(firstname='<script>alert(1)</script>')
        self.assertEqual(self.persisted_user()['firstname'],
                         '&lt;script&gt;alert(1)&lt;/script&gt;')

    def test_database_error_on_insert_returns_generic_500(self):
        """database failures are reported without internal detail"""
        self.users_db.add_user.side_effect = SQLAlchemyError('INSERT failed: ssn=000-00-0001')
        response = self.signup()
        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.get_data(as_text=True), 'failed to create user')

    def test_database_error_on_duplicate_lookup_returns_500_without_insert(self):
        """lookup failure does not fall through to an insert"""
        self.users_db.get_user.side_effect = SQLAlchemyError('connection reset')
        response = self.signup()
        self.assert_rejected(response, 500, 'failed to create user')


if __name__ == '__main__':
    unittest.main()
