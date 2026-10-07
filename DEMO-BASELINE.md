# Prepared demo baseline

This branch intentionally contains incomplete testing for the coverage-improvement demonstration. It is not a measured reproduction of the case study's 30% coverage.

| Service | Starting condition |
| --- | --- |
| Java ledgerwriter | Six successful/basic test methods remain; 15 validation and failure test methods removed. JUnit/Mockito/JaCoCo retained. |
| Python userservice | Four API test methods remain (version, readiness, account creation, login); eight failure/validation methods removed. Database tests and pytest harness retained. |
| Python contacts | Entire test directory, pytest/pytest-cov dependencies, pytest configuration, and Skaffold unit/coverage hooks removed. Runtime dependencies and linting retained. |
| TypeScript audit | No automated test harness, coverage setup, or dedicated CI test job. |
| balancereader / transactionhistory | Existing tests retained. |
| Frontend | Cypress suite retained. Playwright has not been configured. |

The `make test-unit` target explicitly warns that it only exercises the remaining suites; missing harnesses must not be presented as a full green result. Existing browser tests may still exercise service behavior indirectly.

Application behavior, Docker Compose runtime, UI, and audit integration were not changed when preparing these gaps. No production validation was removed.

## Business brief for later test generation

Improve confidence in transaction integrity, authenticated account access, valid contact handling, and audit data minimization. Inspect the implementation, determine missing cases, and design meaningful tests. Establish infrastructure where absent. Exercise failures and persisted outcomes, not just successful HTTP responses. Report uncovered behavior and defects honestly. Do not restore deleted tests from repository history to complete the task.

The preserved pre-removal state is on `demo-reference` at commit `74c9cb733d7bee1b8ee20b69f7b5ccaa98f7cfea`; it is a preparation/review reference, not a completed or fully verified comprehensive suite. The preparation manifest is kept separately from this working branch so it is not an answer sheet for test generation.

## Verification limits

This preparation checks edited Python syntax, contacts dependency-lock consistency, and removal scope. Coverage has not been measured and the retained Java/Python suites have not been executed in this preparation. Source-based backend execution and CI/reporting remain subsequent preparation tasks. No playbook was created in this step.
