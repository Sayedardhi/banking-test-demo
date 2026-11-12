# Bank of Anthos interview workspace

This is Google's Bank of Anthos sample, running locally with fictional data.
The customer feature request is in `demo/FEATURE_REQUEST.md`.

## Working agreements

- Read the existing Flask routes and templates before proposing changes.
- Keep the feature within `src/frontend`. The Java services and database schema
  are outside this ticket's scope.
- Preserve JWT validation. Derive the account from the authenticated token.
- Keep money in integer cents internally. Format decimal amounts at the boundary.
- Reuse the existing styles and Flask/Jinja patterns. Avoid new dependencies.
- Use the existing history and contact service APIs. The history service returns
  up to 100 recent records; this feature searches and exports that available set.
- Use the same filtering rules for the screen and download.
- Never read runtime keys or files under `.demo`, or change the acceptance suite
  to make it pass. Add any extra tests separately under `src/frontend/tests`.
- Run `./demo.sh test` after implementing the feature. Report any failures and the
  checks you actually ran. Finish with the changed files and review considerations.
- Wait for approval of the plan before editing. A human reviews the final diff.
