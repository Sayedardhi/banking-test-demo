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

"""Shared fixtures: a userservice app whose UserDb is a MagicMock (unit layer)."""

import logging
from unittest.mock import patch, mock_open

import pytest

from userservice.userservice import create_app
from userservice.tests.constants import EXAMPLE_PRIVATE_KEY

UNIT_ENV = {
    'VERSION': 'test',
    'TOKEN_EXPIRY_SECONDS': '3600',
    'PRIV_KEY_PATH': 'priv',
    'PUB_KEY_PATH': 'pub',
    'ENABLE_TRACING': 'false',
    'ACCOUNTS_DB_URI': 'postgresql://unused',
}


class RecordingHandler(logging.Handler):
    """Keeps every formatted log line emitted by the app logger."""

    def __init__(self):
        super().__init__(level=logging.DEBUG)
        self.lines = []

    def emit(self, record):
        self.lines.append(record.getMessage())


def build_unit_app(env=None):
    """Create the real Flask app with UserDb replaced by a MagicMock."""
    with patch('userservice.userservice.open', mock_open(read_data='key')), \
            patch('os.environ', {**UNIT_ENV, **(env or {})}), \
            patch('userservice.userservice.UserDb') as mock_db:
        app = create_app()
    app.config['TESTING'] = True
    # Observe the HTTP status a real client would get for unhandled errors.
    app.config['PROPAGATE_EXCEPTIONS'] = False
    app.config['PRIVATE_KEY'] = EXAMPLE_PRIVATE_KEY
    return app, mock_db.return_value


@pytest.fixture(name='unit_app')
def fixture_unit_app():
    """(flask app, test client, mocked UserDb instance)"""
    app, users_db = build_unit_app()
    users_db.get_user.return_value = None
    users_db.generate_accountid.return_value = '1234567890'
    return app, app.test_client(), users_db


@pytest.fixture(name='app_log')
def fixture_app_log(unit_app):
    """Captures the app logger at DEBUG, the most verbose level operators can set."""
    app = unit_app[0]
    handler = RecordingHandler()
    previous = app.logger.level
    app.logger.setLevel(logging.DEBUG)
    app.logger.addHandler(handler)
    yield handler
    app.logger.removeHandler(handler)
    app.logger.setLevel(previous)
