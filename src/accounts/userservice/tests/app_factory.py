"""
Shared builders for userservice unit tests.

Only external boundaries are substituted: the key files, the environment and
the UserDb database adapter. Request handling, validation, hashing and JWT
signing run for real.
"""

import calendar
from datetime import datetime
from unittest.mock import patch, mock_open

import bcrypt

from userservice.userservice import create_app
from userservice.tests.constants import EXAMPLE_PRIVATE_KEY, EXAMPLE_PUBLIC_KEY

FROZEN_NOW = datetime(2026, 1, 15, 9, 30, 0)
FROZEN_EPOCH = calendar.timegm(FROZEN_NOW.utctimetuple())

SYNTHETIC_PASSWORD = 'S3cr3t-Passw0rd!'
SYNTHETIC_SSN = '000-00-0001'

VALID_SIGNUP = {
    'username': 'alice_01',
    'password': SYNTHETIC_PASSWORD,
    'password-repeat': SYNTHETIC_PASSWORD,
    'firstname': 'Alice',
    'lastname': 'Tester',
    'birthday': '1990-04-15',
    'timezone': '-5',
    'address': '1 Synthetic Way',
    'state': 'NY',
    'zip': '10001',
    'ssn': SYNTHETIC_SSN,
}


class FrozenDatetime(datetime):
    """datetime replacement whose utcnow() is fixed, to control token times."""

    @classmethod
    def utcnow(cls):
        return FROZEN_NOW


def default_env(**overrides):
    """Environment that create_app() requires, with optional overrides."""
    env = {
        'VERSION': 'test',
        'TOKEN_EXPIRY_SECONDS': '3600',
        'PRIV_KEY_PATH': 'priv.pem',
        'PUB_KEY_PATH': 'pub.pem',
        'ENABLE_TRACING': 'false',
        'ACCOUNTS_DB_URI': 'postgresql://unit-test-unused',
    }
    env.update(overrides)
    return env


def build_test_app(env=None):
    """Create the real Flask app with a mocked UserDb.

    Returns (app, test_client, mocked UserDb instance).
    """
    keys = {
        'priv.pem': EXAMPLE_PRIVATE_KEY.decode(),
        'pub.pem': EXAMPLE_PUBLIC_KEY.decode(),
    }

    def fake_open(path, *_args, **_kwargs):
        return mock_open(read_data=keys[path])()

    with patch('userservice.userservice.open', side_effect=fake_open), \
            patch('os.environ', env or default_env()), \
            patch('userservice.userservice.UserDb') as user_db_class:
        app = create_app()
    app.config['TESTING'] = True
    return app, app.test_client(), user_db_class.return_value


def stored_user(username='alice_01', password=SYNTHETIC_PASSWORD, accountid='1234567890'):
    """A user row as UserDb.get_user would return it, with a real bcrypt hash."""
    return {
        'accountid': accountid,
        'username': username,
        'passhash': bcrypt.hashpw(password.encode('utf-8'), bcrypt.gensalt(rounds=4)),
        'firstname': 'Alice',
        'lastname': 'Tester',
        'birthday': '1990-04-15',
        'timezone': '-5',
        'address': '1 Synthetic Way',
        'state': 'NY',
        'zip': '10001',
        'ssn': SYNTHETIC_SSN,
    }
