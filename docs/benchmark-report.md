# Benchmark Report

Load test results for `payment-gateway` (the relational baseline), run head-to-head against
[`payment-gateway-evtsrc`](https://github.com/artivisi/payment-gateway-evtsrc) — an event-sourced
comparison implementation (Kafka + embedded RocksDB write path, PostgreSQL CQRS read projection)
built to validate the trade-offs in [`architecture-comparison.md`](architecture-comparison.md).

This file reports this repo's own numbers and how to reproduce them. For the full methodology,
both systems' numbers side by side, and the financial-correctness audit, see
[`payment-gateway-evtsrc/scenarios/perf_benchmark_report.md`](https://github.com/artivisi/payment-gateway-evtsrc/blob/main/scenarios/perf_benchmark_report.md)
(section 8) — that is the authoritative source; this page summarizes it.

## Methodology

- **Load tool**: [k6](https://k6.io) v0.55+, `ramping-arrival-rate` executor, 50 → 500 → 1,000 →
  2,000 TPS target over 90 seconds.
- **Protocol under test**: the real production BSI adapter (`/api/bank/bsi`), full SHA-1 checksum
  verification, six seeded VA/amount pairs (CLOSED, OPEN, INSTALLMENT charge types) — not a
  synthetic or unauthenticated endpoint.
- **Test script**: [`scenarios/suite-rdbms.js`](https://github.com/artivisi/payment-gateway-evtsrc/blob/main/scenarios/suite-rdbms.js),
  which lives in the `payment-gateway-evtsrc` repo alongside its own equivalent script
  (`suite-bsi.js`) so both systems are driven by the same request shape, checksum scheme, VA/amount
  pairs, and ramp profile. Run against this repo's app on port 8080.
- **Run conditions**: `RUN_ID` and `BSI_SHARED_SECRET` are required environment variables (the
  script throws if either is missing — no default secret, no default run identifier). The BSI
  sandbox escrow's `client_secret` (previously `NULL` in seed data — see the note below) must be
  set to a real value matching `BSI_SHARED_SECRET` before running.
- **Hardware**: Apple M5, 10-core, 16GB — shared with other work at the time of the run, not a
  dedicated benchmark host. Treat the numbers as a first real measurement, not a ceiling.

```bash
# from payment-gateway-evtsrc, targeting this repo's app:
RUN_ID=$(date +%Y%m%d%H%M%S) \
BSI_SHARED_SECRET=<real escrow secret> \
k6 run -e RUN_ID="$RUN_ID" -e BSI_SHARED_SECRET="$BSI_SHARED_SECRET" \
  -e TARGET_URL=http://localhost:8080 \
  --summary-export=scenarios/results/$(date +%Y-%m-%d)-rdbms-summary.json \
  --out json=scenarios/results/$(date +%Y-%m-%d)-rdbms-raw.json \
  scenarios/suite-rdbms.js
```

## Results (2026-07-28, run ID `20260728140654`)

| Metric | Value |
|---|---|
| Total requests | 86,582 |
| HTTP error rate | 0.00% |
| Dropped iterations | 42 |
| Effective throughput | 962.0 req/s |
| Min latency | 271 µs |
| Median latency | 674 µs |
| Avg latency | 2.01 ms |
| p90 | 3.11 ms |
| p95 | 5.09 ms |
| p99 | 21.15 ms |
| Max latency | 165.33 ms |
| Peak VUs used | 26 of 130 pre-allocated (2,000 cap) |
| Threshold `p(99)<500ms` | PASS |
| Threshold `http_req_failed<1%` | PASS |
| Accepted payments | 28,953 |
| Rejected (charge already closed) | 57,629 |
| Financial correctness audit | PASS — every accepted payment has exactly one row, every rejection has zero rows, cross-checked against the k6 outcome log independently of this app's own database (`scenarios/verify-correctness.py` in the evtsrc repo) |

Never came close to its allotted VU or throughput ceiling — the pessimistic-lock, single-transaction
write path absorbed the full ramp with headroom to spare. See the linked report above for the
side-by-side comparison against the event-sourced variant, including the median-vs-tail-latency
trade-off between the two designs and a domain-model bug (OPEN charge capping) the two-system
comparison surfaced and that has since been fixed in both this repo's `CLAUDE.md` and the
comparison implementation.

## A note on the test secret

The BSI sandbox escrow (`escrow_account.code = 'BSI'`, `active_environment = 'SANDBOX'`) had a
`NULL` `client_secret` in existing seed data, which is not usable for a real checksum-verified load
test (a `NULL` secret makes the checksum trivially guessable). A fresh, disposable random secret
was generated and written to that one sandbox row for this benchmark, encrypted with this app's own
`SecretCipher` (AES-256-GCM) format, and verified via a live HTTP checksum round-trip before any
load was sent. If re-running this benchmark, either reuse a real secret already configured on that
escrow, or set a new one through the admin UI (preferred) so it goes through the normal encrypted
write path.
