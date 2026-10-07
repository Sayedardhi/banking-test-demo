"""Shared fixtures for the contacts tests: synthetic RSA keys, JWT minting and service config."""
import datetime

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from support import ALICE, LOCAL_ROUTING


def _keypair():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.NoEncryption())
    public = key.public_key().public_bytes(serialization.Encoding.PEM,
                                           serialization.PublicFormat.SubjectPublicKeyInfo)
    return private, public


@pytest.fixture(scope="session")
def signing_keys():
    """The key pair the service trusts (the public half is mounted as PUB_KEY_PATH)."""
    return _keypair()


@pytest.fixture(scope="session")
def foreign_keys():
    """A key pair the service does not trust."""
    return _keypair()


@pytest.fixture
def make_token(signing_keys):
    """Mint an RS256 token like userservice does; overrides allow expired/foreign/forged tokens."""
    def mint(claims=None, key=None, algorithm="RS256", expires_in=3600):
        now = datetime.datetime.now(datetime.timezone.utc)
        payload = {"user": ALICE["user"], "acct": ALICE["acct"], "name": "Synthetic Alice",
                   "iat": now, "exp": now + datetime.timedelta(seconds=expires_in)}
        payload.update(claims or {})
        return jwt.encode(payload, key or signing_keys[0], algorithm=algorithm)
    return mint


@pytest.fixture
def auth(make_token):
    """Authorization header for a user (defaults to alice)."""
    def header(identity=None):
        return {"Authorization": "Bearer " + make_token(identity or ALICE)}
    return header


@pytest.fixture
def service_env(monkeypatch, tmp_path, signing_keys):
    """Environment read by contacts.create_app(); tests override ACCOUNTS_DB_URI as needed."""
    pub = tmp_path / "publickey"
    pub.write_bytes(signing_keys[1])
    monkeypatch.setenv("ENABLE_TRACING", "false")
    monkeypatch.setenv("VERSION", "v-test")
    monkeypatch.setenv("LOCAL_ROUTING_NUM", LOCAL_ROUTING)
    monkeypatch.setenv("PUB_KEY_PATH", str(pub))
    monkeypatch.setenv("ACCOUNTS_DB_URI", "postgresql://unused:unused@127.0.0.1:1/unused")
    return monkeypatch
