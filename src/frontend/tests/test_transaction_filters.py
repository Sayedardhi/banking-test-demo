"""Check the feature contract against the interview's actual transaction fixtures."""
import csv
import datetime
import io
from pathlib import Path
import re
import sys
import unittest

FRONTEND = Path(__file__).resolve().parents[1]
ROOT = FRONTEND.parents[1]
sys.path.insert(0, str(FRONTEND))
from transaction_filters import TransactionFilters, filter_transactions, transactions_csv

ACCOUNT = '1011226111'
LABELS = {'1033623433':'Northstar Coffee', '1055757655':'Harbor Market',
          '1077441377':'Metro Utilities', '9099791699':'Monthly Salary',
          '2000000001':'Studio, North', '2000000002':'=SUM(1,1)'}

def fixture_transactions():
    source = (ROOT / 'demo/seed-ledger.sql').read_text()
    pattern = r"\('(\d+)','(\d+)','(\d+)','(\d+)',(\d+),'([^']+)'\)"
    records = []
    for sender, receiver, from_route, to_route, cents, timestamp in re.findall(pattern, source):
        other = sender if receiver == ACCOUNT else receiver
        records.append({'fromAccountNum':sender, 'toAccountNum':receiver,
                        'fromRoutingNum':from_route, 'toRoutingNum':to_route,
                        'amount':int(cents), 'timestamp':timestamp.replace(' ', 'T')+'+00:00',
                        'accountLabel':LABELS.get(other)})
    return records

class TransactionFilterContract(unittest.TestCase):
    def setUp(self):
        self.records = fixture_transactions()

    def matching(self, **args):
        return filter_transactions(self.records, ACCOUNT, TransactionFilters.from_query(args))

    def test_account_scope_and_newest_first(self):
        rows = self.matching()
        self.assertEqual(len(rows), 22)
        self.assertEqual(rows[0]['timestamp'][:10], '2026-11-10')
        self.assertTrue(all(ACCOUNT in (r['fromAccountNum'],r['toAccountNum']) for r in rows))
        self.assertFalse(any(r['amount']==99999 for r in rows))

    def test_search_label_is_trimmed_and_case_insensitive(self):
        rows = self.matching(q='  NoRtHsTaR  ')
        self.assertEqual(len(rows), 10)
        self.assertTrue(all(r['accountLabel']=='Northstar Coffee' for r in rows))

    def test_search_account_number(self):
        self.assertEqual(len(self.matching(q='1033623433')), 10)

    def test_combined_search_and_inclusive_dates(self):
        rows = self.matching(q='northstar', start_date='2026-11-01', end_date='2026-11-10')
        exported = list(csv.DictReader(io.StringIO(transactions_csv(rows, ACCOUNT))))
        self.assertEqual([r['Date'] for r in exported],
                         ['2026-11-10','2026-11-08','2026-11-07','2026-11-05','2026-11-02'])
        self.assertEqual([r['Amount'] for r in exported],
                         ['-8.25','25.00','-6.50','-9.75','-6.50'])

    def test_single_day_and_no_matches(self):
        self.assertEqual(len(self.matching(start_date='2026-11-10',end_date='2026-11-10')), 1)
        rows = self.matching(q='not-a-real-payee')
        self.assertEqual(rows, [])
        self.assertEqual(list(csv.reader(io.StringIO(transactions_csv(rows, ACCOUNT)))),
                         [['Date','Type','Account','Label','Amount']])

    def test_invalid_dates_and_reversed_ranges(self):
        for args in [{'start_date':'2026-02-30'}, {'end_date':'tomorrow'},
                     {'start_date':'2026-11-10','end_date':'2026-11-01'}]:
            with self.subTest(args=args), self.assertRaises(ValueError):
                TransactionFilters.from_query(args)

    def test_csv_text_escaping_and_formula_neutralization(self):
        rows = list(csv.DictReader(io.StringIO(transactions_csv(self.matching(), ACCOUNT))))
        self.assertTrue(any(r['Label']=='Studio, North' for r in rows))
        formula = next(r for r in rows if r['Account']=='2000000002')
        self.assertEqual(formula['Label'], "'=SUM(1,1)")
        self.assertEqual(formula['Amount'], '-1.00')

    def test_date_boundaries_use_utc(self):
        record = dict(self.records[0], timestamp='2026-11-10T00:30:00+01:00')
        filters = TransactionFilters.from_query({'start_date':'2026-11-09','end_date':'2026-11-09'})
        self.assertEqual(filter_transactions([record],ACCOUNT,filters), [record])

if __name__ == '__main__':
    unittest.main()
