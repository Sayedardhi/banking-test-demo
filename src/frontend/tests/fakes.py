"""Test doubles shared by the unit suites: real requests.Response objects and a URL router."""
import json

from urllib.parse import parse_qs, urlparse

import requests

from tests.helpers import rsa_keypair

KEYS = rsa_keypair()
OTHER_KEYS = rsa_keypair()


def response(status=200, body=None, text=None, url='http://backend.test/'):
    """A real requests.Response so raise_for_status()/json() behave as in production."""
    resp = requests.Response()
    resp.status_code = status
    resp.reason = requests.status_codes._codes.get(status, ('',))[0].upper()  # pylint: disable=protected-access
    resp.url = url
    payload = text if text is not None else json.dumps(body if body is not None else {})
    resp._content = payload.encode()  # pylint: disable=protected-access
    resp.headers['Content-Type'] = 'application/json' if text is None else 'text/plain'
    return resp


class Backends:
    """Routes requests.post/get by URL; records every call for interaction assertions."""

    def __init__(self):
        self.routes = {}
        self.calls = []

    def on(self, method, url_prefix, result):
        """result: a Response, an exception instance, or a callable(url, kwargs)."""
        self.routes[(method, url_prefix)] = result

    def _dispatch(self, method, url, kwargs):
        self.calls.append((method, url, kwargs))
        for (m, prefix), result in sorted(self.routes.items(), key=lambda r: -len(r[0][1])):
            if m == method and url.startswith(prefix):
                if isinstance(result, Exception):
                    raise result
                return result(url, kwargs) if callable(result) else result
        raise requests.exceptions.ConnectionError('no route for ' + url)

    def post(self, url=None, **kwargs):
        return self._dispatch('POST', url, kwargs)

    def get(self, url=None, **kwargs):
        return self._dispatch('GET', url, kwargs)

    def posted(self, prefix):
        return [c for c in self.calls if c[0] == 'POST' and c[1].startswith(prefix)]

    def posted_json(self, prefix):
        out = []
        for _, _, kwargs in self.posted(prefix):
            out.append(kwargs['json'] if 'json' in kwargs else json.loads(kwargs['data']))
        return out


def redirect_msg(resp):
    """Decoded ?msg= of a redirect, or None."""
    return parse_qs(urlparse(resp.headers['Location']).query).get('msg', [None])[0]
