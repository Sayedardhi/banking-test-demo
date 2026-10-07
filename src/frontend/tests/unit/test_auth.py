"""Authentication and session handling: JWT verification, login/logout, signup, OAuth consent."""
import time
from urllib.parse import parse_qs, urlparse

import jwt
import pytest
import requests

from tests.helpers import make_token
from tests.fakes import KEYS, OTHER_KEYS, redirect_msg, response

LOGIN_URI = 'http://userservice.test:8080/login'
USERS_URI = 'http://userservice.test:8080/users'


def _home_backends(backends):
    backends.on('GET', 'http://balancereader.test', response(200, 0))
    backends.on('GET', 'http://transactionhistory.test', response(200, []))
    backends.on('GET', 'http://contacts.test', response(200, []))


def forged_tokens():
    now = int(time.time())
    good = make_token(KEYS[0])
    header, _, signature = good.split('.')
    other_payload = make_token(KEYS[0], acct='1099999999').split('.')[1]
    return {
        'expired': make_token(KEYS[0], iat=now - 7200, ttl=3600),
        'signed by another key': make_token(OTHER_KEYS[0]),
        'unsigned alg=none': jwt.encode({'user': 'alice_test', 'acct': '1011226111', 'name': 'A',
                                         'iat': now, 'exp': now + 3600}, None, algorithm='none'),
        'HS256 with public key as secret': jwt.encode(
            {'user': 'alice_test', 'acct': '1011226111', 'name': 'A', 'iat': now, 'exp': now + 3600},
            'x' * 32, algorithm='HS256'),
        'payload swapped (acct changed)': '.'.join([header, other_payload, signature]),
        'garbage': 'not-a-jwt',
        'empty': '',
    }


FORGED = forged_tokens()


class TestTokenVerification:
    def test_home_without_cookie_redirects_to_login(self, client, backends):
        resp = client.get('/home')
        assert resp.status_code == 302
        assert urlparse(resp.headers['Location']).path == '/login'
        assert backends.calls == [], 'no backend may be queried for an anonymous user'

    def test_root_without_cookie_renders_login_with_200(self, client):
        resp = client.get('/')
        assert resp.status_code == 200
        assert b'id="login-form"' in resp.data

    def test_root_with_valid_token_renders_home(self, auth_client, backends):
        _home_backends(backends)
        resp = auth_client.get('/')
        assert resp.status_code == 200
        assert b'Alice Tester' in resp.data and b'id="current-balance"' in resp.data

    @pytest.mark.parametrize('kind', list(FORGED))
    def test_forged_or_expired_token_is_rejected_everywhere(self, client, backends, kind):
        client.set_cookie('token', FORGED[kind])
        home = client.get('/home')
        assert home.status_code == 302 and urlparse(home.headers['Location']).path == '/login'
        pay = client.post('/payment', data={'account_num': '1033623433', 'amount': '1.00',
                                            'uuid': 'u-1'})
        dep = client.post('/deposit', data={'account': 'add', 'external_account_num': '9099791699',
                                            'external_routing_num': '808889588', 'amount': '1',
                                            'uuid': 'u-2'})
        assert pay.status_code == 401 and dep.status_code == 401
        assert backends.calls == [], f'{kind} token must not reach any backend'

    def test_login_page_with_valid_token_redirects_home(self, auth_client):
        resp = auth_client.get('/login')
        assert resp.status_code == 302 and urlparse(resp.headers['Location']).path == '/home'


class TestLogin:
    def test_success_sets_token_cookie_with_token_lifetime(self, client, backends):
        issued = int(time.time())
        token = make_token(KEYS[0], iat=issued, ttl=1800)
        backends.on('GET', LOGIN_URI, response(200, {'token': token}))
        resp = client.post('/login', data={'username': 'alice_test', 'password': 's3cret-pw'})
        assert resp.status_code == 302 and urlparse(resp.headers['Location']).path == '/home'
        cookie = resp.headers['Set-Cookie']
        assert cookie.startswith('token=' + token)
        assert 'Max-Age=1800' in cookie
        (_, url, kwargs), = backends.calls
        assert url == LOGIN_URI
        assert kwargs['params'] == {'username': 'alice_test', 'password': 's3cret-pw'}

    @pytest.mark.parametrize('failure', [
        response(401, text='Unauthorized', url=LOGIN_URI),
        response(404, text='user not found', url=LOGIN_URI),
        response(500, text='db down', url=LOGIN_URI),
        requests.exceptions.ConnectionError('userservice down'),
        requests.exceptions.Timeout('slow'),
    ], ids=['401', '404', '500', 'connection-error', 'timeout'])
    def test_failure_redirects_with_message_and_sets_no_cookie(self, client, backends, failure):
        backends.on('GET', LOGIN_URI, failure)
        resp = client.post('/login', data={'username': 'alice_test', 'password': 'wrong'})
        assert resp.status_code == 302
        assert redirect_msg(resp) == 'Login Failed'
        assert 'Set-Cookie' not in resp.headers

    def test_oauth_login_redirects_to_consent_with_state(self, client, backends):
        backends.on('GET', LOGIN_URI, response(200, {'token': make_token(KEYS[0])}))
        resp = client.post('/login?response_type=code&state=st-1&redirect_uri=http://app.test/cb'
                           '&app_name=Budget', data={'username': 'alice_test', 'password': 'pw'})
        location = urlparse(resp.headers['Location'])
        assert location.path == '/consent'
        assert parse_qs(location.query) == {'state': ['st-1'], 'redirect_uri': ['http://app.test/cb'],
                                            'app_name': ['Budget']}

    def test_logout_expires_token_and_consent_cookies(self, auth_client):
        resp = auth_client.post('/logout')
        assert urlparse(resp.headers['Location']).path == '/login'
        cookies = resp.headers.getlist('Set-Cookie')
        for name in ('token', 'consented'):
            cookie = next(c for c in cookies if c.startswith(name + '='))
            assert cookie.startswith(name + '=;') and 'Expires=Thu, 01 Jan 1970' in cookie


class TestSignup:
    FORM = {'username': 'bob_test', 'password': 'pw-1', 'password-repeat': 'pw-1',
            'firstname': 'Bob', 'lastname': 'Tester', 'birthday': '1990-01-01',
            'timezone': 'GMT', 'address': '1 Test St', 'state': 'NY', 'zip': '10004',
            'ssn': '000-00-0000'}

    def test_created_user_is_logged_in(self, client, backends):
        backends.on('POST', USERS_URI, response(201, text=''))
        backends.on('GET', LOGIN_URI, response(200, {'token': make_token(KEYS[0], user='bob_test')}))
        resp = client.post('/signup', data=self.FORM)
        assert urlparse(resp.headers['Location']).path == '/home'
        assert resp.headers['Set-Cookie'].startswith('token=')
        (_, _, kwargs), = backends.posted(USERS_URI)
        assert dict(kwargs['data']) == self.FORM
        (_, _, login_kwargs), = [c for c in backends.calls if c[1] == LOGIN_URI]
        assert login_kwargs['params'] == {'username': 'bob_test', 'password': 'pw-1'}

    @pytest.mark.parametrize('failure', [
        response(409, text='user already exists'), response(400, text='passwords do not match'),
        response(500, text='boom'), requests.exceptions.ConnectionError('down')],
        ids=['409', '400', '500', 'connection-error'])
    def test_rejected_signup_does_not_log_in(self, client, backends, failure):
        backends.on('POST', USERS_URI, failure)
        resp = client.post('/signup', data=self.FORM)
        assert redirect_msg(resp) == 'Error: Account creation failed'
        assert 'Set-Cookie' not in resp.headers
        assert not [c for c in backends.calls if c[1] == LOGIN_URI]

    def test_signup_page_for_authenticated_user_redirects_home(self, auth_client):
        resp = auth_client.get('/signup')
        assert urlparse(resp.headers['Location']).path == '/home'

    def test_signup_page_renders_for_anonymous_user(self, client):
        assert b'id="signup-form"' in client.get('/signup').data


class TestOAuth:
    @pytest.fixture
    def oauth_env(self, env):
        env.setenv('REGISTERED_OAUTH_CLIENT_ID', 'client-123')
        env.setenv('ALLOWED_OAUTH_REDIRECT_URI', 'http://app.test/cb')
        return env

    def test_unregistered_client_id_is_rejected(self, oauth_env, make_app):  # pylint: disable=unused-argument
        resp = make_app().test_client().get(
            '/login?response_type=code&client_id=evil&redirect_uri=http://app.test/cb')
        assert redirect_msg(resp) == 'Error: Invalid client_id'

    def test_unregistered_redirect_uri_is_rejected(self, oauth_env, make_app):  # pylint: disable=unused-argument
        resp = make_app().test_client().get(
            '/login?response_type=code&client_id=client-123&redirect_uri=http://evil.test/cb')
        assert redirect_msg(resp) == 'Error: Invalid redirect_uri'

    def test_registered_client_renders_login_or_consent(self, oauth_env, make_app, token):  # pylint: disable=unused-argument
        client = make_app().test_client()
        url = '/login?response_type=code&client_id=client-123&redirect_uri=http://app.test/cb&state=s'
        assert b'id="login-form"' in client.get(url).data
        client.set_cookie('token', token)
        assert urlparse(client.get(url).headers['Location']).path == '/consent'

    def test_consent_page_requires_login(self, client):
        resp = client.get('/consent?state=s&redirect_uri=http://app.test/cb&app_name=Budget')
        assert urlparse(resp.headers['Location']).path == '/login'

    def test_consent_page_renders_for_logged_in_user(self, auth_client):
        resp = auth_client.get('/consent?state=s&redirect_uri=http://app.test/cb&app_name=Budget')
        assert resp.status_code == 200 and b'Budget' in resp.data

    def test_granted_consent_exchanges_token_for_auth_code(self, auth_client, backends, token):
        callback = response(302, text='')
        callback.headers['Location'] = 'http://app.test/done?code=abc'
        backends.on('POST', 'http://app.test/cb', callback)
        resp = auth_client.post('/consent?consent=true&state=s-9&redirect_uri=http://app.test/cb')
        assert resp.headers['Location'] == 'http://app.test/done?code=abc'
        assert 'consented=true' in ' '.join(resp.headers.getlist('Set-Cookie'))
        (_, _, kwargs), = backends.posted('http://app.test/cb')
        assert kwargs['data'] == {'state': 's-9', 'id_token': token}
        assert kwargs['allow_redirects'] is False

    def test_previous_consent_skips_the_form(self, auth_client, backends):
        callback = response(302, text='')
        callback.headers['Location'] = 'http://app.test/done?code=xyz'
        backends.on('POST', 'http://app.test/cb', callback)
        auth_client.set_cookie('consented', 'true')
        resp = auth_client.get('/consent?state=s&redirect_uri=http://app.test/cb')
        assert resp.headers['Location'] == 'http://app.test/done?code=xyz'

    @pytest.mark.parametrize('callback', [response(200, text='ok'),
                                          requests.exceptions.ConnectionError('down')],
                             ids=['unexpected-200', 'connection-error'])
    def test_callback_failure_reports_server_error(self, auth_client, backends, callback):
        backends.on('POST', 'http://app.test/cb', callback)
        resp = auth_client.post('/consent?consent=true&state=s&redirect_uri=http://app.test/cb')
        assert resp.headers['Location'] == 'http://app.test/cb#error=server_error'

    def test_denied_consent_does_not_send_token(self, auth_client, backends):
        resp = auth_client.post('/consent?consent=false&state=s&redirect_uri=http://app.test/cb')
        assert resp.headers['Location'] == 'http://app.test/cb#error=access_denied'
        assert backends.calls == []
