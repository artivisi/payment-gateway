# Test data fixtures

CSV fixtures for functional tests and demos. Loaded **explicitly** by tests via `CsvFixtures`
(seeded through the domain services) — not as global Flyway test data, because the test suite
isolates by unique keys per test and a shared seed would collide. (Contrast: `aplikasi-akunting`
seeds fixed datasets via `db/test/integration/V9xx__*.sql`; that fits its fixed-dataset tests, not
ours.)

All data here is synthetic and neutral — no real client names, credentials, endpoints, or bank data
(see governance in `CLAUDE.md`).

## Files

| File | Columns | Use |
|---|---|---|
| `escrow-accounts.csv` | code, provider, hostingModel, transport, authScheme, activeEnvironment, companyId, vaPrefix, vaDigitLength, settlement* | test bank/escrow data |
| `consumers.csv` | code, name, clientId, clientSecret, webhookUrl, status | test consumer |
| `charges.csv` | consumerReference, consumerCode, payerName, chargeType, amount, escrowCode, vaNumber | test VA/charge data (what is owed) |
| `payments.csv` | escrowCode, vaNumber, amount, bankReference, transactionTime | payments the gateway already recorded |
| `reconciliation/settlement-sample.csv` | vaNumber, bankReference, amount, transactionTime | settlement statement to upload (`POST /reconciliations/import`) |
| `reconciliation/expected-discrepancies.csv` | vaNumber, bankReference, type, note | documents the expected reconciliation outcome |

## Reconciliation scenario (period 2026-06-25)

`charges` + `payments` set up the gateway state; `settlement-sample.csv` is what the bank reports.
Reconciling the two yields exactly one of each outcome (see `expected-discrepancies.csv`):
matched, duplicate, recovered (paid-not-notified), amount mismatch, unmatched credit, and
notified-not-settled. Exercised by `ReconciliationCsvImportTest`.
