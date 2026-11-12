# Customer request: find and export transactions

FinTechCo customers need to find payments in their recent account activity and
download matching records for their own bookkeeping.

Today the account page shows an unfiltered transaction list. Add this feature to
the existing page while keeping the bank's current design and payment behavior.

## Acceptance criteria

1. A signed-in customer can search by payee/contact label or counterparty account
   number. Matching is case-insensitive and ignores surrounding search whitespace.
2. Optional `start_date` and `end_date` filters use YYYY-MM-DD and include both
   boundary dates, interpreted in UTC. Combine these with the text search.
3. The form retains the selected values, shows a result count, and has a Clear link.
4. No matches produces a helpful empty state. Invalid or reversed dates return 400
   with a useful message.
5. A Download CSV action exports exactly the matching available transactions, in
   the same newest-first order as the screen. Use the endpoint `/transactions/export`
   and query keys `q`, `start_date`, and `end_date`.
6. CSV columns are `Date,Type,Account,Label,Amount`. Dates use YYYY-MM-DD. Type is
   Credit or Debit relative to this customer, and Amount is a signed decimal with
   two places. The bank stores cents as integers.
7. Use proper CSV escaping for commas and quotes, and neutralize spreadsheet
   formulas in text cells. The fixtures include examples for both.
8. Export requires a valid login, never takes an account ID from the request, and
   never exposes another customer's records. Mark the download as an attachment
   and prevent caching.
9. Preserve existing login, balance, deposit, payment and logout behavior. No new
   dependencies or backend/schema changes are needed for this ticket.

This is a recent-history feature. The existing service supplies at most 100
records per customer. Full-ledger search and pagination would be a separate ticket.

## Run and check

The app is already running at http://localhost:8088. Saved source edits reload.
Run `./demo.sh test` to exercise the browser-facing routes with real service data.

Demo login: `testuser` / `bankofanthos`.

## Example to show the customer

Search `northstar`, from `2026-11-01` through `2026-11-10`.
Expected: 5 transactions, including one credit. The matching CSV has 5 data rows.
Clearing the filters returns all 22 seeded transactions for this customer.
