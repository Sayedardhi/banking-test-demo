"""Read-only filtering and export of an authenticated customer's recent history."""
from __future__ import annotations
import csv
import datetime
from dataclasses import dataclass
from decimal import Decimal
import io

UTC = datetime.timezone.utc

@dataclass(frozen=True)
class TransactionFilters:
    q: str = ''
    start_date: datetime.date | None = None
    end_date: datetime.date | None = None

    @classmethod
    def from_query(cls, args):
        def date_value(name):
            value = args.get(name, '').strip()
            if not value:
                return None
            try:
                result = datetime.date.fromisoformat(value)
                if result.isoformat() != value:
                    raise ValueError
                return result
            except ValueError as error:
                raise ValueError('Use valid dates in YYYY-MM-DD format.') from error
        q = args.get('q', '').strip()
        if len(q) > 128:
            raise ValueError('Keep the search to 128 characters or fewer.')
        result = cls(q, date_value('start_date'), date_value('end_date'))
        if result.start_date and result.end_date and result.start_date > result.end_date:
            raise ValueError('The start date must be on or before the end date.')
        return result

    def query_values(self):
        return {key: value for key, value in {
            'q': self.q,
            'start_date': self.start_date.isoformat() if self.start_date else '',
            'end_date': self.end_date.isoformat() if self.end_date else '',
        }.items() if value}

def transaction_time(transaction):
    value = datetime.datetime.fromisoformat(transaction['timestamp'].replace('Z', '+00:00'))
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC)

def filter_transactions(transactions, account_id, filters):
    if transactions is None:
        return None
    result = []
    query = filters.q.casefold()
    for transaction in transactions:
        if transaction['toAccountNum'] == account_id:
            other = transaction['fromAccountNum']
        elif transaction['fromAccountNum'] == account_id:
            other = transaction['toAccountNum']
        else:
            continue
        label = transaction.get('accountLabel') or ''
        if query and query not in label.casefold() and query not in other.casefold():
            continue
        day = transaction_time(transaction).date()
        if filters.start_date and day < filters.start_date:
            continue
        if filters.end_date and day > filters.end_date:
            continue
        result.append(transaction)
    return sorted(result, key=transaction_time, reverse=True)

def csv_text_cell(value):
    text = str(value or '')
    if text.lstrip().startswith(('=', '+', '-', '@')) or text.startswith(('\t', '\r', '\n')):
        return "'" + text
    return text

def transactions_csv(transactions, account_id):
    output = io.StringIO(newline='')
    writer = csv.writer(output)
    writer.writerow(['Date', 'Type', 'Account', 'Label', 'Amount'])
    for transaction in transactions:
        credit = transaction['toAccountNum'] == account_id
        other = transaction['fromAccountNum'] if credit else transaction['toAccountNum']
        cents = transaction['amount'] if credit else -transaction['amount']
        writer.writerow([
            transaction_time(transaction).date().isoformat(),
            'Credit' if credit else 'Debit', csv_text_cell(other),
            csv_text_cell(transaction.get('accountLabel')),
            format(Decimal(cents) / Decimal(100), '.2f'),
        ])
    return output.getvalue()
