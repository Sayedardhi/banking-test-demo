# pylint: disable=unnecessary-lambda
"""Home page aggregation, backend-failure degradation, formatting helpers and platform metadata."""
import requests
import pytest

from tests.fakes import response
from tests.helpers import ACCOUNT, EXTERNAL_ACCOUNT, OTHER_ACCOUNT, load_frontend

BALANCE = f'http://balancereader.test:8080/balances/{ACCOUNT}'
HISTORY = f'http://transactionhistory.test:8080/transactions/{ACCOUNT}'
CONTACTS = 'http://contacts.test:8080/contacts/alice_test'
TS = '2026-10-07T10:15:30.000+0000'


def tx(frm, to, amount, frm_routing='883745000'):
    return {'transactionId': 1, 'fromAccountNum': frm, 'fromRoutingNum': frm_routing,
            'toAccountNum': to, 'toRoutingNum': '883745000', 'amount': amount, 'timestamp': TS}


class TestHome:
    def test_renders_balance_history_and_contact_labels(self, auth_client, backends, token):
        backends.on('GET', BALANCE, response(200, 123456))
        backends.on('GET', HISTORY, response(200, [tx(ACCOUNT, OTHER_ACCOUNT, 2500),
                                                   tx(EXTERNAL_ACCOUNT, ACCOUNT, 10000, '808889588')]))
        backends.on('GET', CONTACTS, response(200, [
            {'label': 'Landlord', 'account_num': OTHER_ACCOUNT, 'routing_num': '883745000',
             'is_external': False},
            {'label': 'Payroll', 'account_num': EXTERNAL_ACCOUNT, 'routing_num': '808889588',
             'is_external': True}]))
        page = auth_client.get('/home').get_data(as_text=True)
        assert '$1,234.56' in page
        assert '-$25.00' in page and '$100.00' in page
        assert 'Landlord' in page and 'Payroll' in page
        assert 'Oct' in page
        for url in (BALANCE, HISTORY, CONTACTS):
            (_, _, kwargs), = [c for c in backends.calls if c[1] == url]
            assert kwargs['headers'] == {'Authorization': 'Bearer ' + token}
            assert kwargs['timeout'] == 4

    @pytest.mark.parametrize('failure', [response(500, text='down'),
                                         requests.exceptions.ConnectionError('refused'),
                                         requests.exceptions.Timeout('slow')],
                             ids=['500', 'connection-error', 'timeout'])
    def test_backend_failures_degrade_to_placeholders(self, auth_client, backends, failure):
        for url in (BALANCE, HISTORY, CONTACTS):
            backends.on('GET', url, failure)
        resp = auth_client.get('/home')
        assert resp.status_code == 200
        page = resp.get_data(as_text=True)
        assert '$---' in page
        assert 'Error: Could Not Load Transactions' in page

    def test_message_query_parameter_is_escaped(self, auth_client, backends):
        backends.on('GET', BALANCE, response(200, 0))
        for url in (HISTORY, CONTACTS):
            backends.on('GET', url, response(200, []))
        page = auth_client.get('/home?msg=<script>alert(1)</script>').get_data(as_text=True)
        assert '<script>alert(1)</script>' not in page
        assert '&lt;script&gt;alert(1)&lt;/script&gt;' in page


class TestFormatting:
    @pytest.fixture
    def jinja(self, app):
        return app.jinja_env.globals

    @pytest.mark.parametrize('cents,text', [(None, '$---'), (0, '$0.00'), (1, '$0.01'),
                                            (-1, '-$0.01'), (123456789, '$1,234,567.89'),
                                            (-100000, '-$1,000.00')])
    def test_currency(self, jinja, cents, text):
        assert jinja['format_currency'](cents) == text

    def test_timestamp_parts(self, jinja):
        assert jinja['format_timestamp_day'](TS) == '07'
        assert jinja['format_timestamp_month'](TS) == 'Oct'

    def test_malformed_timestamp_raises(self, jinja):
        with pytest.raises(ValueError):
            jinja['format_timestamp_day']('2026-10-07')


class TestServiceEndpoints:
    def test_ready_version_whereami(self, client):
        assert client.get('/ready').get_data(as_text=True) == 'ok'
        assert client.get('/version').get_data(as_text=True) == 'v-test'
        assert client.get('/whereami').get_data(as_text=True).startswith('Cluster: unknown, Pod: ')

    def test_metadata_server_values_are_used_when_available(self, make_app, backends):
        backends.on('GET', 'http://metadata.test/computeMetadata/v1/instance/attributes/cluster-name',
                    response(200, text='bank-cluster'))
        backends.on('GET', 'http://metadata.test/computeMetadata/v1/instance/zone',
                    response(200, text='projects/1/zones/us-east1-b'))
        body = make_app().test_client().get('/whereami').get_data(as_text=True)
        assert body.startswith('Cluster: bank-cluster') and body.endswith('Zone: us-east1-b')

    @pytest.mark.parametrize('platform,label', [('alibaba', 'Alibaba Cloud'), ('AWS', 'AWS'),
                                                ('azure', 'Azure'), ('gcp', 'Google Cloud'),
                                                ('local', 'Local'), ('onprem', 'On-Premises')])
    def test_supported_platform_banner(self, env, make_app, platform, label):
        env.setenv('ENV_PLATFORM', platform)
        assert label in make_app().test_client().get('/login').get_data(as_text=True)

    def test_unsupported_platform_is_ignored(self, env, make_app):
        env.setenv('ENV_PLATFORM', 'mainframe')
        page = make_app().test_client().get('/login').get_data(as_text=True)
        assert 'mainframe' not in page.lower()

    def test_tracing_enabled_instruments_app(self, env, make_app, monkeypatch):
        frontend = load_frontend()
        calls = []
        monkeypatch.setattr(frontend, 'CloudTraceSpanExporter', lambda: calls.append('exporter'))
        monkeypatch.setattr(frontend, 'BatchSpanProcessor', lambda exporter: calls.append('batch'))
        monkeypatch.setattr(frontend.trace, 'set_tracer_provider', lambda provider: None)
        monkeypatch.setattr(frontend.trace, 'get_tracer_provider',
                            lambda: type('P', (), {'add_span_processor': lambda self, p: None})())
        for name in ('FlaskInstrumentor', 'RequestsInstrumentor', 'Jinja2Instrumentor'):
            monkeypatch.setattr(frontend, name, lambda n=name: type(n, (), {
                'instrument_app': lambda self, a: calls.append(n),
                'instrument': lambda self: calls.append(n)})())
        env.setenv('ENABLE_TRACING', 'true')
        make_app()
        assert calls == ['exporter', 'batch', 'FlaskInstrumentor', 'RequestsInstrumentor',
                         'Jinja2Instrumentor']


class TestApiCall:
    def test_request_errors_are_logged_and_return_none(self, caplog):
        import api_call  # pylint: disable=import-outside-toplevel
        import logging  # pylint: disable=import-outside-toplevel
        from unittest import mock  # pylint: disable=import-outside-toplevel
        call = api_call.ApiCall('balance', api_call.ApiRequest('http://x.test/b', {}, 1),
                                logging.getLogger('t'))
        for error in (requests.exceptions.ConnectionError('refused'), ValueError('bad url')):
            with mock.patch('api_call.get', side_effect=error):
                assert call.make_call() is None
        assert caplog.text.count('Error getting balance') == 2

    def test_traced_executor_propagates_context_and_results(self):
        from opentelemetry import trace  # pylint: disable=import-outside-toplevel
        from traced_thread_pool_executor import \
            TracedThreadPoolExecutor  # pylint: disable=import-outside-toplevel
        with TracedThreadPoolExecutor(trace.get_tracer('t'), max_workers=2) as pool:
            assert [f.result() for f in [pool.submit(pow, 2, 10), pool.submit(str.upper, 'ok')]] \
                == [1024, 'OK']
