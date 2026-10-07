# Banking Test Demo

A local banking application for demonstrating critical-path test improvement across services. Uses synthetic accounts and transactions only; this is not a real bank.

## Run locally

Install and start Docker Desktop (or Docker Engine with Compose), then run:

```sh
./scripts/start-local.sh
```

Open **http://localhost:8080**. Demo login: **testuser / bankofanthos**.

The script creates a local JWT key pair (gitignored). Only the frontend is published, bound to localhost. Two PostgreSQL databases remain inside the Compose network. No cloud account or Kubernetes is required. First startup downloads pinned upstream release images; ARM machines run these amd64 images under emulation.

```sh
docker compose ps                    # service status
docker compose logs --tail 30        # diagnostics
docker compose down                 # stop; preserve database contents
```

## Current baseline

- Java: ledger writer, balance reader, transaction history.
- Python: user authentication, contacts, original frontend.
- PostgreSQL: accounts and ledger databases.
- Upstream unit/database tests and Cypress E2E tests remain intact.
- Neutral Demo Bank branding; cloud tracing/metrics and traffic generator are not enabled in the local setup.

**This baseline runs pinned upstream images.** The frontend template directory is mounted for branding edits. Changes to application source require a rebuilt image; Compose does not automatically run edited Java/Python source. A TypeScript frontend and deliberately prepared testing gaps are subsequent demo adaptations, not implemented in this baseline.

## Demo preparation

Keep the original tests as references, derive acceptance criteria from them, and then deliberately remove selected tests on a separate demo branch. Disclose the prepared gaps. Measure coverage rather than assuming the case study's 30% baseline. Demonstrate critical behaviors and reviewable execution evidence, not only coverage percentages.

## Attribution

Adapted from [Bank of Anthos](https://github.com/GoogleCloudPlatform/bank-of-anthos), copyright Google LLC and contributors, under the Apache License 2.0. Original license and source notices are retained. Local modifications add Compose startup and neutral presentation. See [LICENSE](LICENSE). Existing upstream cloud deployment documentation remains available in `docs/` as reference.
