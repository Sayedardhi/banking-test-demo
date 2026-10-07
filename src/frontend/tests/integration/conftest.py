"""Integration fixtures: the real frontend over HTTP, the real audit service on a temporary SQLite
database, and recording stub backends for ledgerwriter/balancereader/transactionhistory/contacts/
userservice (outside this suite's declared scope; their own suites test them)."""
import json
import os
import pathlib
import shutil
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

import pytest
import requests
from werkzeug.serving import make_server

from tests.helpers import ACCOUNT, LOCAL_ROUTING, load_frontend, make_token, rsa_keypair

REPO = pathlib.Path(__file__).resolve().parents[4]
AUDIT_DIST = REPO / 'src' / 'audit' / 'dist' / 'server.js'
AUDIT_TOKEN = 'integration-audit-token'
KEYS = rsa_keypair()


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def wait_for(url, timeout=20.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if requests.get(url, timeout=1).status_code == 200:
                return
        except requests.exceptions.RequestException:
            pass
        time.sleep(0.1)
    raise RuntimeError(f'{url} did not become ready within {timeout}s')


class Bank:  # pylint: disable=too-many-instance-attributes
    """Controlled substitute for the ledger/account backends: applies accepted transactions to an
    in-memory ledger so balance and history reflect what the frontend submitted."""

    def __init__(self):
        self.lock = threading.Lock()
        self.address = None
        self.reset()

    def reset(self):
        with getattr(self, 'lock', threading.Lock()):
            self.requests = []
            self.transactions = []
            self.balances = {ACCOUNT: 100000}
            self.ledger_status = 201
            self.ledger_body = 'ok'
            self.contacts = []
            self.tokens = {}

    def ledger_posts(self):
        return [r for r in self.requests if r['method'] == 'POST' and r['path'] == '/transactions']

    def handle(self, method, path, headers, body):  # pylint: disable=too-many-return-statements
        with self.lock:
            self.requests.append({'method': method, 'path': path, 'headers': headers, 'body': body})
            parsed = urlparse(path)
            parts = parsed.path.strip('/').split('/')
            if method == 'POST' and parsed.path == '/transactions':
                if self.ledger_status != 201:
                    return self.ledger_status, self.ledger_body
                txn = json.loads(body)
                txn['timestamp'] = '2026-10-07T10:15:30.000+0000'
                self.transactions.insert(0, txn)
                for acct, sign in ((txn['fromAccountNum'], -1), (txn['toAccountNum'], 1)):
                    if acct in self.balances or txn['toRoutingNum'] == LOCAL_ROUTING:
                        self.balances[acct] = self.balances.get(acct, 0) + sign * txn['amount']
                return 201, 'ok'
            if method == 'GET' and parts[0] == 'balances':
                return 200, json.dumps(self.balances.get(parts[1], 0))
            if method == 'GET' and parts[0] == 'transactions':
                acct = parts[1]
                return 200, json.dumps([t for t in self.transactions
                                        if acct in (t['fromAccountNum'], t['toAccountNum'])])
            if parts[0] == 'contacts':
                if method == 'POST':
                    self.contacts.append(json.loads(body))
                    return 201, ''
                return 200, json.dumps(self.contacts)
            if method == 'GET' and parsed.path == '/login':
                return 200, json.dumps({'token': make_token(KEYS[0])})
            return 404, 'not found'


@pytest.fixture(scope='session')
def bank():
    state = Bank()

    class Handler(BaseHTTPRequestHandler):
        def _serve(self):
            length = int(self.headers.get('Content-Length') or 0)
            body = self.rfile.read(length).decode() if length else ''
            status, payload = state.handle(self.command, self.path, dict(self.headers), body)
            data = payload.encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        do_GET = do_POST = _serve  # noqa: N815

        def log_message(self, *args):  # silence per-request stderr noise
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    state.address = f'127.0.0.1:{server.server_address[1]}'
    yield state
    server.shutdown()


@pytest.fixture(scope='session')
def audit_service(tmp_path_factory):
    """The real audit service (src/audit, Node 24) on a throwaway SQLite file."""
    node = shutil.which('node')
    if node is None or not AUDIT_DIST.exists():
        raise RuntimeError('audit service not built: run `npm ci && npm run build` in src/audit '
                           'with Node 24 (scripts/report-tests.sh does this)')
    db = tmp_path_factory.mktemp('audit') / 'audit.sqlite'
    port = free_port()
    log = open(db.parent / 'audit.log', 'w', encoding='utf-8')  # pylint: disable=consider-using-with
    proc = subprocess.Popen([node, str(AUDIT_DIST)], stdout=log, stderr=subprocess.STDOUT,
                            env={**os.environ, 'PORT': str(port), 'AUDIT_DB_PATH': str(db),
                                 'AUDIT_TOKEN': AUDIT_TOKEN})
    url = f'http://127.0.0.1:{port}'
    try:
        wait_for(url + '/health')
        yield type('Audit', (), {'url': url, 'db': db, 'log': db.parent / 'audit.log',
                                 'events': staticmethod(lambda: requests.get(
                                     url + '/events', timeout=5,
                                     headers={'Authorization': 'Bearer ' + AUDIT_TOKEN}
                                 ).json()['events'])})
    finally:
        proc.terminate()
        proc.wait(timeout=10)
        log.close()


@pytest.fixture(scope='session')
def frontend_server(bank, audit_service, tmp_path_factory):  # pylint: disable=redefined-outer-name
    pub = tmp_path_factory.mktemp('keys') / 'publickey'
    pub.write_bytes(KEYS[1])
    env = {'TRANSACTIONS_API_ADDR': bank.address, 'BALANCES_API_ADDR': bank.address,
           'HISTORY_API_ADDR': bank.address, 'CONTACTS_API_ADDR': bank.address,
           'USERSERVICE_API_ADDR': bank.address, 'METADATA_SERVER': bank.address,
           'PUB_KEY_PATH': str(pub), 'LOCAL_ROUTING_NUM': LOCAL_ROUTING, 'ENABLE_TRACING': 'false',
           'VERSION': 'v-integration', 'SCHEME': 'http',
           'AUDIT_SERVICE_URL': audit_service.url, 'AUDIT_TOKEN': AUDIT_TOKEN}
    saved = {k: os.environ.get(k) for k in env}
    os.environ.update(env)
    app = load_frontend().create_app()
    server = make_server('127.0.0.1', 0, app, threaded=True)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{server.server_port}'
    wait_for(base + '/ready')
    yield base
    server.shutdown()
    for key, value in saved.items():
        if value is None:
            os.environ.pop(key, None)
        else:
            os.environ[key] = value


@pytest.fixture
def frontend(frontend_server, bank):  # pylint: disable=redefined-outer-name
    bank.reset()
    return frontend_server


@pytest.fixture
def customer(frontend):  # pylint: disable=redefined-outer-name
    """A browser-like session logged in through the real /login endpoint."""
    session = requests.Session()
    resp = session.post(frontend + '/login', data={'username': 'alice_test', 'password': 'pw'},
                        allow_redirects=False, timeout=10)
    assert resp.status_code == 302 and session.cookies.get('token')
    return session
