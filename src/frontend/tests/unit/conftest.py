"""Unit fixtures: the Flask app with every outbound HTTP call replaced at the `requests` boundary."""
import logging
from unittest import mock

import pytest

from tests.fakes import KEYS, Backends
from tests.helpers import AUDIT_TOKEN, BACKEND_ENV, LOCAL_ROUTING, load_frontend, make_token

@pytest.fixture
def env(monkeypatch, tmp_path):
    pub = tmp_path / 'publickey'
    pub.write_bytes(KEYS[1])
    values = {**BACKEND_ENV, 'PUB_KEY_PATH': str(pub), 'LOCAL_ROUTING_NUM': LOCAL_ROUTING,
              'ENABLE_TRACING': 'false', 'VERSION': 'v-test', 'SCHEME': 'http',
              'AUDIT_SERVICE_URL': 'http://audit.test:8080', 'AUDIT_TOKEN': AUDIT_TOKEN,
              'METADATA_SERVER': 'metadata.test'}
    for key in ('ENV_PLATFORM', 'REGISTERED_OAUTH_CLIENT_ID', 'ALLOWED_OAUTH_REDIRECT_URI',
                'BANK_NAME', 'CLUSTER_NAME', 'POD_ZONE'):
        monkeypatch.delenv(key, raising=False)
    for key, value in values.items():
        monkeypatch.setenv(key, value)
    return monkeypatch


@pytest.fixture
def backends():
    load_frontend()
    fake = Backends()
    with mock.patch('requests.post', side_effect=fake.post), \
            mock.patch('requests.get', side_effect=fake.get), \
            mock.patch('api_call.get', side_effect=fake.get), \
            mock.patch('frontend_app.sleep') as _:
        yield fake


@pytest.fixture
def make_app(env, backends):  # pylint: disable=redefined-outer-name,unused-argument
    """Builds the app; platform-metadata lookups made during create_app() are not recorded."""
    frontend = load_frontend()

    def build():
        app = frontend.create_app()
        app.config['TESTING'] = True
        app.logger.setLevel(logging.DEBUG)
        backends.calls.clear()
        return app
    return build


@pytest.fixture
def app(make_app):  # pylint: disable=redefined-outer-name
    return make_app()


@pytest.fixture
def client(app):  # pylint: disable=redefined-outer-name
    return app.test_client()


@pytest.fixture
def token():
    return make_token(KEYS[0])


@pytest.fixture
def auth_client(client, token):  # pylint: disable=redefined-outer-name
    client.set_cookie('token', token)
    return client
