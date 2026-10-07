"""Shared helpers for contacts tests: synthetic RSA keys, JWTs and contacts."""
# pylint: disable=redefined-outer-name,missing-function-docstring

import datetime

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

LOCAL_ROUTING = "883745000"
EXTERNAL_ROUTING = "111000025"
ALICE = {"user": "alice-synth", "acct": "9000000001"}
BOB = {"user": "bob-synth", "acct": "9000000002"}


def generate_keypair():
    """Return (private_pem, public_pem) for a fresh RSA key."""
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private_pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    public_pem = key.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return private_pem, public_pem


def make_token(private_pem, user, acct, expires_in=3600, algorithm="RS256"):
    """Sign a userservice-style JWT for the given user."""
    now = datetime.datetime.now(datetime.timezone.utc)
    payload = {
        "user": user,
        "acct": acct,
        "name": "Synthetic User",
        "iat": now,
        "exp": now + datetime.timedelta(seconds=expires_in),
    }
    return jwt.encode(payload, private_pem, algorithm=algorithm)


def auth(token):
    """Authorization header in the format sent by the frontend."""
    return {"Authorization": "Bearer " + token}


def contact(label="Payroll", account_num="1234567890",
            routing_num=EXTERNAL_ROUTING, is_external=True):
    """A valid synthetic contact request body."""
    return {
        "label": label,
        "account_num": account_num,
        "routing_num": routing_num,
        "is_external": is_external,
    }
