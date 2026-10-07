"""
Unit tests for login (GET /login): credential checks, JWT issuance,
claims, expiry, signature and error handling.
"""

import base64
import json
import unittest
from unittest.mock import patch

import jwt
from sqlalchemy.exc import SQLAlchemyError

from userservice.tests.app_factory import (
    build_test_app, default_env, stored_user, FrozenDatetime, FROZEN_EPOCH,
    VALID_SIGNUP, SYNTHETIC_PASSWORD, SYNTHETIC_SSN)
from userservice.tests.constants import EXAMPLE_PUBLIC_KEY, generate_rsa_key


class TestLogin(unittest.TestCase):
    """GET /login with the database adapter mocked and real bcrypt/JWT."""

    def setUp(self):
        self.app, self.client, self.users_db = build_test_app()
        self.user = stored_user()
        self.users_db.get_user.return_value = self.user

    def login(self, username='alice_01', password=SYNTHETIC_PASSWORD):
        """Call /login with the given credentials."""
        return self.client.get('/login', query_string={'username': username,
                                                        'password': password})

    def frozen_login(self, **kwargs):
        """Login with utcnow() fixed so token times are deterministic."""
        with patch('userservice.userservice.datetime', FrozenDatetime):
            return self.login(**kwargs)

    @staticmethod
    def decode(token, **kwargs):
        """Verify a token with the service's public key."""
        return jwt.decode(token, key=EXAMPLE_PUBLIC_KEY, algorithms=['RS256'], **kwargs)

    def test_valid_credentials_return_only_a_token(self):
        """response body contains the token and nothing else"""
        response = self.login()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(list(response.get_json()), ['token'])
        self.users_db.get_user.assert_called_once_with('alice_01')

    def test_token_is_rs256_with_exact_identity_claims(self):
        """claims identify the user/account and carry no sensitive data"""
        token = self.frozen_login().get_json()['token']
        self.assertEqual(jwt.get_unverified_header(token)['alg'], 'RS256')
        claims = self.decode(token, options={'verify_exp': False})
        self.assertEqual(claims, {
            'user': 'alice_01',
            'acct': '1234567890',
            'name': 'Alice Tester',
            'iat': FROZEN_EPOCH,
            'exp': FROZEN_EPOCH + 3600,
        })

    def test_token_expiry_follows_token_expiry_seconds(self):
        """TOKEN_EXPIRY_SECONDS controls the session lifetime"""
        app, client, users_db = build_test_app(default_env(TOKEN_EXPIRY_SECONDS='60'))
        users_db.get_user.return_value = self.user
        self.assertEqual(app.config['EXPIRY_SECONDS'], 60)
        with patch('userservice.userservice.datetime', FrozenDatetime):
            response = client.get('/login', query_string={'username': 'alice_01',
                                                          'password': SYNTHETIC_PASSWORD})
        claims = self.decode(response.get_json()['token'], options={'verify_exp': False})
        self.assertEqual(claims['exp'] - claims['iat'], 60)

    def test_fresh_token_verifies_and_expired_token_is_rejected(self):
        """tokens are valid now and rejected once past exp"""
        self.assertEqual(self.decode(self.login().get_json()['token'])['user'], 'alice_01')
        expired = self.frozen_login().get_json()['token']
        with self.assertRaises(jwt.ExpiredSignatureError):
            self.decode(expired)

    def test_token_signed_by_another_key_is_rejected(self):
        """downstream services must not accept tokens they cannot verify"""
        token = self.login().get_json()['token']
        _, other_public_key = generate_rsa_key()
        with self.assertRaises(jwt.InvalidSignatureError):
            jwt.decode(token, key=other_public_key, algorithms=['RS256'])

    def test_tampered_token_payload_is_rejected(self):
        """changing the account claim invalidates the signature"""
        header, payload, signature = self.login().get_json()['token'].split('.')
        claims = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
        claims['acct'] = '9999999999'
        forged = base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b'=').decode()
        with self.assertRaises(jwt.InvalidSignatureError):
            self.decode('.'.join([header, forged, signature]))

    def test_token_does_not_contain_password_hash_or_ssn(self):
        """sensitive user attributes are never placed in the token"""
        token = self.login().get_json()['token']
        raw = json.dumps(self.decode(token))
        self.assertNotIn(SYNTHETIC_SSN, raw)
        self.assertNotIn(SYNTHETIC_PASSWORD, raw)
        self.assertNotIn(self.user['passhash'].decode(), raw)

    def test_wrong_password_returns_401_without_token(self):
        """real bcrypt verification rejects a wrong password"""
        response = self.login(password='not-the-password')
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.get_data(as_text=True), 'invalid login')

    def test_password_check_is_case_sensitive(self):
        """a case-changed password is rejected"""
        response = self.login(password=SYNTHETIC_PASSWORD.lower())
        self.assertEqual(response.status_code, 401)

    def test_unknown_user_returns_404_without_token(self):
        """login for a non-existent user fails"""
        self.users_db.get_user.return_value = None
        response = self.login(username='nobody')
        self.assertEqual(response.status_code, 404)
        self.assertEqual(response.get_data(as_text=True), 'user nobody does not exist')

    def test_database_error_returns_500_without_token(self):
        """dependency failure does not authenticate the user"""
        self.users_db.get_user.side_effect = SQLAlchemyError('connection refused')
        response = self.login()
        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.get_data(as_text=True), 'failed to retrieve user information')

    def test_missing_credentials_are_rejected_as_client_error(self):
        """a request without username or password is a 400, not a server error"""
        self.app.config['PROPAGATE_EXCEPTIONS'] = False
        for params in ({'username': 'alice_01'}, {'password': SYNTHETIC_PASSWORD}, {}):
            with self.subTest(params=params):
                response = self.client.get('/login', query_string=params)
                self.assertEqual(response.status_code, 400)

    def test_password_with_markup_characters_round_trips_signup_to_login(self):
        """signup and login sanitize the password identically"""
        password = 'p&ss<w>rd"1'
        self.users_db.get_user.return_value = None
        self.users_db.generate_accountid.return_value = '1234567890'
        form = dict(VALID_SIGNUP, password=password, **{'password-repeat': password})
        self.assertEqual(self.client.post('/users', data=form).status_code, 201)
        self.users_db.get_user.return_value = self.users_db.add_user.call_args[0][0]
        self.assertEqual(self.login(password=password).status_code, 200)
        self.assertEqual(self.login(password='p&ss').status_code, 401)


if __name__ == '__main__':
    unittest.main()
