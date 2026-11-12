#!/usr/bin/env python3
"""Acceptance checks against the running sample bank. Python standard library only."""
import csv
import io
import os
import re
import unittest
from http.cookiejar import CookieJar
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener

BASE = os.environ.get('DEMO_URL', 'http://127.0.0.1:8088').rstrip('/')

def session(username='testuser', password='bankofanthos'):
    client = build_opener(HTTPCookieProcessor(CookieJar()))
    payload = urlencode({'username': username, 'password': password}).encode()
    response = client.open(Request(BASE + '/login', data=payload), timeout=30)
    if '/home' not in response.url:
        raise AssertionError('Could not log in to the demo bank')
    response.read()
    return client

def fetch(client, path, **query):
    url = BASE + path + ('?' + urlencode(query) if query else '')
    try:
        response = client.open(url, timeout=30)
    except HTTPError as response:
        return response.code, response.headers, response.read().decode()
    return response.status, response.headers, response.read().decode()

def records(client, **query):
    status, headers, body = fetch(client, '/transactions/export', **query)
    if status != 200 or 'text/csv' not in headers.get('Content-Type', ''):
        raise AssertionError(f'Expected a CSV download, got {status} {headers.get("Content-Type")}')
    return headers, list(csv.DictReader(io.StringIO(body)))

class BankingFeatureAcceptance(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = session()
        cls.bob = session('bob')

    def test_01_original_account_still_works(self):
        status, _, body = fetch(self.client, '/home')
        self.assertEqual(status, 200)
        for label in ['Checking Account', 'Current Balance', 'Deposit Funds', 'Send Payment']:
            self.assertIn(label, body)
        self.assertNotIn('Could Not Load Transactions', body)

    def test_02_search_form_has_controls_and_download(self):
        _, _, body = fetch(self.client, '/home')
        for field in ['q', 'start_date', 'end_date']:
            self.assertRegex(body, r'name=[\"\']' + field + r'[\"\']')
        self.assertIn('/transactions/export', body)
        self.assertIn('Clear', body)

    def test_03_case_insensitive_label_search(self):
        _, rows = records(self.client, q='  nOrThStAr  ')
        self.assertEqual(len(rows), 10)
        self.assertTrue(all(r['Label'] == 'Northstar Coffee' for r in rows))

    def test_04_search_by_counterparty_account(self):
        _, rows = records(self.client, q='1033623433')
        self.assertEqual(len(rows), 10)
        self.assertTrue(all(r['Account'] == '1033623433' for r in rows))

    def test_05_inclusive_dates_and_combined_filters(self):
        _, rows = records(self.client, q='northstar', start_date='2026-11-01', end_date='2026-11-10')
        self.assertEqual(len(rows), 5)
        self.assertEqual([r['Date'] for r in rows], ['2026-11-10','2026-11-08','2026-11-07','2026-11-05','2026-11-02'])
        self.assertEqual([r['Amount'] for r in rows], ['-8.25','25.00','-6.50','-9.75','-6.50'])

    def test_06_single_day_range(self):
        _, rows = records(self.client, start_date='2026-11-10', end_date='2026-11-10')
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['Amount'], '-8.25')

    def test_07_empty_results_and_header_only_csv(self):
        _, rows = records(self.client, q='payee-that-does-not-exist')
        self.assertEqual(rows, [])
        status, _, body = fetch(self.client, '/home', q='payee-that-does-not-exist')
        self.assertEqual(status, 200)
        self.assertRegex(body.lower(), r'no (transactions|results).*match')

    def test_08_invalid_and_reversed_dates_are_rejected(self):
        for path in ['/home', '/transactions/export']:
            for query in [{'start_date':'2026-02-30'}, {'start_date':'tomorrow'},
                          {'start_date':'2026-11-10','end_date':'2026-11-01'}]:
                with self.subTest(path=path, query=query):
                    status, _, _ = fetch(self.client, path, **query)
                    self.assertEqual(status, 400)

    def test_09_export_requires_authentication(self):
        client = build_opener()
        status, headers, body = fetch(client, '/transactions/export')
        self.assertTrue(status in (401,403) or 'login' in body.lower())
        self.assertNotIn('text/csv', headers.get('Content-Type', ''))

    def test_10_tampered_token_is_rejected(self):
        client = build_opener()
        client.addheaders = [('Cookie', 'token=not-a-valid-token')]
        status, headers, body = fetch(client, '/transactions/export')
        self.assertTrue(status in (401,403) or 'login' in body.lower())
        self.assertNotIn('text/csv', headers.get('Content-Type', ''))

    def test_11_cannot_select_another_customer(self):
        _, own = records(self.client)
        _, attacked = records(self.client, account_id='1055757655', account='1055757655')
        _, bob = records(self.bob)
        self.assertEqual(own, attacked)
        self.assertEqual(len(own), 22)
        self.assertFalse(any(r['Amount'] == '-999.99' for r in own))
        self.assertTrue(any(r['Amount'] == '-999.99' for r in bob))

    def test_12_csv_download_headers(self):
        headers, rows = records(self.client)
        self.assertIn('attachment', headers.get('Content-Disposition','').lower())
        self.assertIn('no-store', headers.get('Cache-Control','').lower())
        self.assertEqual(list(rows[0]), ['Date','Type','Account','Label','Amount'])

    def test_13_csv_escapes_commas_and_neutralizes_formulas(self):
        _, rows = records(self.client)
        self.assertTrue(any(r['Label'] == 'Studio, North' for r in rows))
        formula = next(r for r in rows if r['Account'] == '2000000002')
        self.assertEqual(formula['Label'], "'=SUM(1,1)")
        self.assertEqual(formula['Amount'], '-1.00')

    def test_14_html_and_download_show_the_same_matches(self):
        query = {'q':'northstar','start_date':'2026-11-01','end_date':'2026-11-10'}
        _, rows = records(self.client, **query)
        status, _, body = fetch(self.client, '/home', **query)
        self.assertEqual(status, 200)
        self.assertEqual(len(re.findall(r'<tr\b', body))-1, len(rows))
        self.assertIn('Northstar Coffee', body)
        self.assertNotIn('Harbor Market', body.split('<!-- Deposit Modal -->')[0])
        self.assertIn('2026-11-01', body)
        self.assertIn('2026-11-10', body)

if __name__ == '__main__':
    print(f'Checking the running bank at {BASE}', flush=True)
    unittest.main(verbosity=2)
