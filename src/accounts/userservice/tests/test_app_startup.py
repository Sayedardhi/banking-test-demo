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

"""Unit tests for create_app configuration and fail-fast startup."""

from unittest.mock import patch, mock_open

import pytest
from sqlalchemy.exc import OperationalError

from userservice.userservice import create_app
from userservice.tests.conftest import UNIT_ENV


def test_keys_and_expiry_are_loaded_from_configured_paths():
    files = {'/keys/priv': 'PRIVATE', '/keys/pub': 'PUBLIC'}
    env = {**UNIT_ENV, 'PRIV_KEY_PATH': '/keys/priv', 'PUB_KEY_PATH': '/keys/pub', 'TOKEN_EXPIRY_SECONDS': '90'}
    with patch('userservice.userservice.open', side_effect=lambda p, *_: mock_open(read_data=files[p])()), \
            patch('os.environ', env), patch('userservice.userservice.UserDb') as users_db:
        app = create_app()
    assert app.config['PRIVATE_KEY'] == 'PRIVATE'
    assert app.config['PUBLIC_KEY'] == 'PUBLIC'
    assert app.config['EXPIRY_SECONDS'] == 90
    users_db.assert_called_once()
    assert users_db.call_args.args[0] == 'postgresql://unused'


def test_database_connection_failure_stops_the_service():
    with patch('userservice.userservice.open', mock_open(read_data='k')), patch('os.environ', UNIT_ENV), \
            patch('userservice.userservice.UserDb', side_effect=OperationalError('connect', {}, Exception('down'))):
        with pytest.raises(SystemExit) as stopped:
            create_app()
    assert stopped.value.code == 1


def test_missing_signing_key_file_prevents_startup():
    with patch('os.environ', {**UNIT_ENV, 'PRIV_KEY_PATH': '/nonexistent/privatekey'}), \
            patch('userservice.userservice.UserDb'):
        with pytest.raises(FileNotFoundError):
            create_app()


def test_tracing_enabled_instruments_flask():
    with patch('userservice.userservice.open', mock_open(read_data='k')), \
            patch('os.environ', {**UNIT_ENV, 'ENABLE_TRACING': 'true'}), \
            patch('userservice.userservice.UserDb'), \
            patch('userservice.userservice.CloudTraceSpanExporter') as exporter, \
            patch('userservice.userservice.FlaskInstrumentor') as instrumentor, \
            patch('userservice.userservice.trace'):
        app = create_app()
    exporter.assert_called_once()
    instrumentor.return_value.instrument_app.assert_called_once_with(app)
