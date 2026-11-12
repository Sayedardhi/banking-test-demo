# Demo Bank banking feature demo

This is Google's actual Bank of Anthos source, with a local interview setup. The
Python frontend talks to the real Java banking services and PostgreSQL databases.
All accounts and transactions are fictional. No Google Cloud account is required.

Upstream: https://github.com/GoogleCloudPlatform/bank-of-anthos
Pinned source: `db35fea9fd090150e2398106aadb475576f80d94`
Runtime images: the upstream `v0.6.11` images, pinned by digest in `demo/prepare.py`.

## Current preparation status

The feature request, fixed fixtures, launch commands and acceptance suite are
prepared. Full Docker startup and the HTTP acceptance suite still require
verification. The separate reference implementation also requires end-to-end
validation before it can be relied on in an interview.

## Get this interview setup

```sh
git clone --branch claude-demo/start https://github.com/Sayedardhi/banking-test-demo.git
cd banking-test-demo
```

The earlier `demo-baseline`, `demo-reference` and other branches in this fork
belong to a separate demo exercise. This setup uses the `claude-demo/*` branches.

## Start your rehearsal

From this folder, run:

```sh
./demo.sh start
./demo.sh check
```

Open http://localhost:8088 and sign in with `testuser` / `bankofanthos`.
The starting code intentionally has no transaction-search or CSV-export feature.

Open a terminal in this folder and start `claude`. Paste the first message from
`demo/PROMPT.txt`. Review its plan, then paste the second message when ready.
Source changes reload automatically. Refresh the browser after Claude finishes.

The customer ticket is `demo/FEATURE_REQUEST.md`. `CLAUDE.md` supplies the team's
working agreements. `demo/acceptance_test.py` checks the feature through the
running web app against the actual banking services.

## Your 10-minute flow

1. **0:00-1:00** Show the account screen. Say: "Customers can see their recent
   activity, but they need a way to find a payment and download matching records."
2. **1:00-3:00** Give Claude the customer request. Let it inspect the existing
   authentication, history calls and templates, then review its plan.
3. **3:00-6:00** Approve implementation. While it works, explain that the change
   should reuse the signed-in account and the existing services.
4. **6:00-8:00** Show the checks Claude actually ran. In the UI, search `northstar`
   from **2026-11-01** to **2026-11-10**. There should be **5 results**, including
   one credit. Download the CSV and confirm the same five records.
5. **8:00-10:00** Clear the filters to return to **22 transactions**. Inspect the
   diff. Ask Claude to explain account isolation and what a reviewer should check.
   Close by linking this workflow to time-to-accepted-change in your pilot.

If useful, ask during review: "What happens if someone supplies a different
account ID in the download URL?" The existing session must determine the account.

## Verify the finished feature

```sh
./demo.sh test
```

These checks fail on the starting code because the requested feature is missing.
They check filtering, inclusive dates, signed monetary amounts, matching screen
and CSV results, empty states, authentication, account isolation, CSV quoting and
spreadsheet formula handling. Keep the suite intact during the live exercise.

## Completed fallback

```sh
./demo.sh reference
./demo.sh test reference
```

The reference is configured at http://localhost:8089 with the same login and
backend services. Its code is on `claude-demo/reference` and a separate checkout
under `.demo/reference`. The starting code is on `claude-demo/start` at port 8088.

If you switch during the presentation, say: "I'll use a prepared reference so we
can inspect the completed behavior and discuss the review." The reference is a
fallback and rehearsal aid; Claude's live output will vary.

## Reset between rehearsals

```sh
./demo.sh reset
```

This saves the current commit in a dated backup branch, stashes uncommitted and
untracked rehearsal work, then restores the `claude-demo-start` starting point
on `claude-demo/live`. Refresh
the browser. Your backup branches and `git stash list` retain previous attempts.
The runtime and reference checkout are ignored by Git and are not reset.

Keep demo transactions unchanged during rehearsals if you want the fixed counts.
The reset command resets source code, not database contents.

## Runtime controls

```sh
./demo.sh status
./demo.sh logs
./demo.sh stop
```

The bank listens only on localhost. Docker Desktop must be running. The first
start downloads the service images; subsequent starts reuse them. Browser assets
are cached in `src/frontend/static/vendor` so the page does not rely on CDNs.

This ticket covers the recent-history set the upstream service supplies, at most
100 transactions. Discuss full-ledger search, pagination, date/time policy and
export size limits as follow-up work before a production rollout.
