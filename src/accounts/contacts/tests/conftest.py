"""Fixtures shared by unit and integration layers."""
# pylint: disable=redefined-outer-name,missing-function-docstring

import pytest

from tests.helpers import generate_keypair


@pytest.fixture(scope="session")
def keypair():
    """Synthetic RSA keypair standing in for the userservice JWT signer."""
    return generate_keypair()


@pytest.fixture(scope="session")
def public_key_path(tmp_path_factory, keypair):
    """Public key written to disk, as mounted from the jwt-key secret."""
    path = tmp_path_factory.mktemp("keys") / "publickey"
    path.write_bytes(keypair[1])
    return str(path)


@pytest.fixture
def service_env(monkeypatch, public_key_path):
    """Environment the service reads at startup (see README)."""
    def apply(db_uri):
        monkeypatch.setenv("VERSION", "test-version")
        monkeypatch.setenv("LOCAL_ROUTING_NUM", "883745000")
        monkeypatch.setenv("PUB_KEY_PATH", public_key_path)
        monkeypatch.setenv("ENABLE_TRACING", "false")
        monkeypatch.setenv("ACCOUNTS_DB_URI", db_uri)
    return apply
