#!/usr/bin/env python3
"""Prepare a local interview environment from the upstream pinned images."""
import base64
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HERE = ROOT / 'demo'

def image(manifest):
    # Release image digests copied from this checkout's Kubernetes manifests.
    name, digest = {
        'accounts-db.yaml': ('accounts-db', 'e86d6533bcd05bf9e4d7d495bf03b0a5ca571f526f3a419572b6df2c7913d588'),
        'ledger-db.yaml': ('ledger-db', 'b434822c3122bc3f4681741fc863c02f7bb2f476b0d6b307e2282b49d896c3dd'),
        'userservice.yaml': ('userservice', '4fcd6638b5ba9ac29096e83ea04f4ff280d1c66874908c7e1156337cad459dc0'),
        'contacts.yaml': ('contacts', '67be31ff16e6e8de0ca0f8ee8fa9c23a900d89b2127d9877e69c488384ef8c2a'),
        'balance-reader.yaml': ('balancereader', '42a51d724d0537a5214b653d63c00fd60eb9d8ee0df3f396716c9e47262ae1ee'),
        'transaction-history.yaml': ('transactionhistory', '609acd4d93aed03139a59e3c4f58bedb695cf4649dbe6fae636d2f45bad3d811'),
        'ledger-writer.yaml': ('ledgerwriter', 'b66efed587c295788369459898e7ae5a5e15150c3fcd782170c19a8c4b31059d'),
        'frontend.yaml': ('frontend', '5746f018f3d85475e25b8283bf8fb476e2501196bbe71e3ce144527e7280e7ae'),
    }[manifest]
    return f'us-central1-docker.pkg.dev/bank-of-anthos-ci/bank-of-anthos/{name}:v0.6.11@sha256:{digest}'

def main():
    keys = ROOT / '.demo' / 'keys'
    keys.mkdir(parents=True, exist_ok=True)
    source = (ROOT / 'extras/jwt/jwt-secret.yaml').read_text()
    for upstream, local in [('jwtRS256.key', 'privatekey'), ('jwtRS256.key.pub', 'publickey')]:
        encoded = re.search(r'^\s+' + re.escape(upstream) + r':\s*(\S+)', source, re.M).group(1)
        path = keys / local
        path.write_bytes(base64.b64decode(encoded.strip('"\'')))
        path.chmod(0o600)
    shared = {'VERSION': 'interview-demo', 'PORT': '8080', 'ENABLE_TRACING': 'false',
              'ENABLE_METRICS': 'false', 'LOG_LEVEL': 'warning', 'LOCAL_ROUTING_NUM': '883745000',
              'PUB_KEY_PATH': '/tmp/.ssh/publickey', 'NAMESPACE': 'interview-local'}
    ledger = {'SPRING_DATASOURCE_URL': 'jdbc:postgresql://ledger-db:5432/postgresdb',
              'SPRING_DATASOURCE_USERNAME': 'admin', 'SPRING_DATASOURCE_PASSWORD': 'password',
              'POLL_MS': '100', 'CACHE_SIZE': '1000', 'CACHE_MINUTES': '60', 'HISTORY_LIMIT': '100',
              'JAVA_TOOL_OPTIONS': '-Xms64m -Xmx256m'}
    accounts = {'ACCOUNTS_DB_URI': 'postgresql://accounts-admin:accounts-pwd@accounts-db:5432/accounts-db'}
    apis = {'TRANSACTIONS_API_ADDR': 'ledgerwriter:8080', 'BALANCES_API_ADDR': 'balancereader:8080',
            'HISTORY_API_ADDR': 'transactionhistory:8080', 'CONTACTS_API_ADDR': 'contacts:8080',
            'USERSERVICE_API_ADDR': 'userservice:8080'}
    def service(manifest, env=None, depends=None):
        # The Java services derive a container name from HOSTNAME by cutting at the first '-'
        # (Kubernetes pod naming); Docker's default hostname has no '-', which crashes them.
        return {'image': image(manifest), 'platform': 'linux/amd64',
                'hostname': manifest.removesuffix('.yaml') + '-local',
                'environment': {**shared, **(env or {})}, 'volumes': ['./.demo/keys:/tmp/.ssh:ro'],
                'depends_on': depends or {}, 'restart': 'unless-stopped'}
    def database(manifest, user, password, name, scripts):
        return {'image': image(manifest), 'platform': 'linux/amd64',
                'environment': {'POSTGRES_USER': user, 'POSTGRES_PASSWORD': password, 'POSTGRES_DB': name,
                                'USE_DEMO_DATA': 'False', 'LOCAL_ROUTING_NUM': '883745000'},
                'volumes': scripts,
                'healthcheck': {'test': ['CMD-SHELL', f'pg_isready -U {user} -d {name}'],
                                'interval': '3s', 'timeout': '3s', 'retries': 40},
                'restart': 'unless-stopped'}
    services = {
        'accounts-db': database('accounts-db.yaml', 'accounts-admin', 'accounts-pwd', 'accounts-db', [
            './demo/accounts-init.sh:/docker-entrypoint-initdb.d/1-load-testdata.sh:ro',
            './src/accounts/accounts-db/initdb/1-load-testdata.sql:/demo/accounts.sql:ro',
            './demo/seed-contacts.sql:/demo/contacts.sql:ro']),
        'ledger-db': database('ledger-db.yaml', 'admin', 'password', 'postgresdb', [
            './demo/ledger-init.sh:/docker-entrypoint-initdb.d/1_create_transactions.sh:ro',
            './demo/seed-ledger.sql:/demo/ledger.sql:ro']),
        'userservice': service('userservice.yaml', {**accounts, 'PRIV_KEY_PATH': '/tmp/.ssh/privatekey',
            'TOKEN_EXPIRY_SECONDS': '28800'}, {'accounts-db': {'condition': 'service_healthy'}}),
        'contacts': service('contacts.yaml', accounts, {'accounts-db': {'condition': 'service_healthy'}}),
        'balancereader': service('balance-reader.yaml', ledger, {'ledger-db': {'condition': 'service_healthy'}}),
        'transactionhistory': service('transaction-history.yaml', ledger, {'ledger-db': {'condition': 'service_healthy'}}),
        'ledgerwriter': service('ledger-writer.yaml', {**ledger, **apis}, {'ledger-db': {'condition': 'service_healthy'}})
    }
    def frontend(path, port):
        return {**service('frontend.yaml', {**apis, 'SCHEME': 'http', 'BANK_NAME': 'FinTechCo Demo Bank',
             'ENV_PLATFORM': 'local', 'CLUSTER_NAME': 'interview-local', 'POD_ZONE': 'laptop',
             'METADATA_SERVER': '127.0.0.1:1', 'DEFAULT_USERNAME': 'testuser',
             'DEFAULT_PASSWORD': 'bankofanthos'},
             {name: {'condition': 'service_started'} for name in ['userservice', 'contacts', 'balancereader', 'transactionhistory', 'ledgerwriter']}),
             'working_dir': '/workspace/frontend',
             'volumes': ['./.demo/keys:/tmp/.ssh:ro', f'{path}:/workspace/frontend:ro'],
             'command': ['sh', '-c', 'exec gunicorn --reload --reload-extra-file templates/index.html -b 0.0.0.0:8080 --threads 4 --access-logfile - "frontend:create_app()"'],
             'ports': [f'127.0.0.1:{port}:8080']}
    services['frontend'] = frontend('./src/frontend', 8088)
    services['reference'] = {**frontend('./.demo/reference/src/frontend', 8089), 'profiles': ['reference']}
    (ROOT / 'compose.demo.json').write_text(json.dumps({'name': 'fintechco-interview', 'services': services}, indent=2) + '\n')
    print('Prepared local services and the upstream sample JWT key pair.')

if __name__ == '__main__':
    main()
