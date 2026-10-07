"""Shared synthetic fixtures for the frontend test suites (no real customer data)."""
import time

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

LOCAL_ROUTING = '883745000'
ACCOUNT = '1011226111'
OTHER_ACCOUNT = '1033623433'
EXTERNAL_ACCOUNT = '9099791699'
EXTERNAL_ROUTING = '808889588'
AUDIT_TOKEN = 'unit-test-audit-token'

BACKEND_ENV = {
    'TRANSACTIONS_API_ADDR': 'ledgerwriter.test:8080',
    'BALANCES_API_ADDR': 'balancereader.test:8080',
    'HISTORY_API_ADDR': 'transactionhistory.test:8080',
    'CONTACTS_API_ADDR': 'contacts.test:8080',
    'USERSERVICE_API_ADDR': 'userservice.test:8080',
}


def rsa_keypair():
    """Returns (private_pem, public_pem) for a fresh 2048-bit RSA key."""
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private_pem = key.private_bytes(serialization.Encoding.PEM,
                                    serialization.PrivateFormat.PKCS8,
                                    serialization.NoEncryption())
    public_pem = key.public_key().public_bytes(serialization.Encoding.PEM,
                                               serialization.PublicFormat.SubjectPublicKeyInfo)
    return private_pem, public_pem


def make_token(private_pem, user='alice_test', acct=ACCOUNT, name='Alice Tester',
               ttl=3600, iat=None):
    """Signs a JWT with the same claims userservice issues."""
    issued = int(time.time()) if iat is None else iat
    claims = {'user': user, 'acct': acct, 'name': name, 'iat': issued, 'exp': issued + ttl}
    return jwt.encode(claims, private_pem, algorithm='RS256')


def load_frontend():
    """Imports frontend.py as `frontend_app`.

    pytest collects src/frontend/__init__.py as a package named `frontend`, which shadows the
    frontend.py module; loading it by path under a distinct name avoids the collision.
    """
    import importlib.util  # pylint: disable=import-outside-toplevel
    import pathlib  # pylint: disable=import-outside-toplevel
    import sys  # pylint: disable=import-outside-toplevel
    if 'frontend_app' not in sys.modules:
        path = pathlib.Path(__file__).resolve().parent.parent / 'frontend.py'
        spec = importlib.util.spec_from_file_location('frontend_app', path)
        module = importlib.util.module_from_spec(spec)
        sys.modules['frontend_app'] = module
        spec.loader.exec_module(module)
    return sys.modules['frontend_app']
