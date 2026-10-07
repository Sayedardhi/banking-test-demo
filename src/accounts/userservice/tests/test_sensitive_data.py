"""
Unit tests that passwords and SSNs are not written to logs or returned
in responses.
"""

import unittest

from userservice.tests.app_factory import (
    build_test_app, stored_user, VALID_SIGNUP, SYNTHETIC_PASSWORD, SYNTHETIC_SSN)


class TestSensitiveData(unittest.TestCase):
    """Sensitive-field handling across signup and login."""

    def setUp(self):
        self.app, self.client, self.users_db = build_test_app()
        self.users_db.get_user.return_value = None
        self.users_db.generate_accountid.return_value = '1234567890'

    def captured(self, level, action):
        """Run action while capturing the app logger at level; return joined text."""
        with self.assertLogs(self.app.logger, level=level) as logs:
            action()
        return '\n'.join(logs.output)

    def assert_no_secrets(self, text):
        """Neither the password nor the SSN appears in text."""
        self.assertNotIn(SYNTHETIC_PASSWORD, text)
        self.assertNotIn(SYNTHETIC_SSN, text)

    def test_signup_logs_at_debug_level_omit_password_and_ssn(self):
        """LOG_LEVEL=debug must not turn logs into a credential store"""
        text = self.captured('DEBUG', lambda: self.client.post('/users', data=VALID_SIGNUP))
        self.assert_no_secrets(text)

    def test_signup_logs_at_default_info_level_omit_password_and_ssn(self):
        """default production log level"""
        text = self.captured('INFO', lambda: self.client.post('/users', data=VALID_SIGNUP))
        self.assertIn('Successfully created user.', text)
        self.assert_no_secrets(text)

    def test_rejected_signup_error_logs_omit_password_and_ssn(self):
        """error logs for validation failures stay free of secrets"""
        form = dict(VALID_SIGNUP, **{'password-repeat': 'different'})
        text = self.captured('ERROR', lambda: self.client.post('/users', data=form))
        self.assertIn('passwords do not match', text)
        self.assert_no_secrets(text)
        self.assertNotIn('different', text)

    def test_login_logs_at_debug_level_omit_password(self):
        """successful and failed logins never log the submitted password"""
        self.users_db.get_user.return_value = stored_user()

        def logins():
            self.client.get('/login', query_string={'username': 'alice_01',
                                                    'password': SYNTHETIC_PASSWORD})
            self.client.get('/login', query_string={'username': 'alice_01',
                                                    'password': 'Wrong-Guess-42'})

        text = self.captured('DEBUG', logins)
        self.assert_no_secrets(text)
        self.assertNotIn('Wrong-Guess-42', text)

    def test_signup_responses_never_echo_password_or_ssn(self):
        """success, validation and conflict responses omit secrets"""
        responses = [self.client.post('/users', data=VALID_SIGNUP),
                     self.client.post('/users', data=dict(VALID_SIGNUP, username='x'))]
        self.users_db.get_user.return_value = stored_user()
        responses.append(self.client.post('/users', data=VALID_SIGNUP))
        self.assertEqual([r.status_code for r in responses], [201, 400, 409])
        for response in responses:
            self.assert_no_secrets(response.get_data(as_text=True))

    def test_login_responses_never_echo_password_ssn_or_hash(self):
        """success and failure responses omit secrets"""
        user = stored_user()
        self.users_db.get_user.return_value = user
        for password in (SYNTHETIC_PASSWORD, 'Wrong-Guess-42'):
            body = self.client.get('/login', query_string={
                'username': 'alice_01', 'password': password}).get_data(as_text=True)
            self.assert_no_secrets(body)
            self.assertNotIn('Wrong-Guess-42', body)
            self.assertNotIn(user['passhash'].decode(), body)


if __name__ == '__main__':
    unittest.main()
