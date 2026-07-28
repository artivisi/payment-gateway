# Benchmark Report

Load test results for `payment-gateway` (the relational baseline), run head-to-head against
[`payment-gateway-evtsrc`](https://github.com/artivisi/payment-gateway-evtsrc) — an event-sourced
comparison implementation (Kafka + embedded RocksDB write path, PostgreSQL CQRS read projection)
built to validate the trade-offs in [`architecture-comparison.md`](architecture-comparison.md).

This file reports this repo's own numbers and how to reproduce them. For the full methodology,
both systems' numbers side by side, and the financial-correctness audit, see
[`payment-gateway-evtsrc/scenarios/perf_benchmark_report.md`](https://github.com/artivisi/payment-gateway-evtsrc/blob/main/scenarios/perf_benchmark_report.md) —
that is the authoritative source; this page summarizes it.

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
  sandbox escrow's `client_secret` must be set to a real value matching `BSI_SHARED_SECRET` before
  running.
- **Fresh database**: the app's database (both schema and containers) was rebuilt from scratch
  immediately before this run — `docker compose down` (including its volume) followed by
  `docker compose up --build`, then `scenarios/seed-db-direct.py`, then a freshly generated BSI
  secret encrypted and written to the escrow row and verified via a live checksum round-trip.
- **Hardware**: Apple M5, 10-core, 16GB — shared with other work at the time of the run, not a
  dedicated benchmark host.

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

## Results (2026-07-29)

| Metric | Value |
|---|---|
| Total requests | 86,439 |
| HTTP error rate | 0.00% |
| Dropped iterations | 185 |
| Effective throughput | 960.3 req/s |
| Min latency | 308 µs |
| Median latency | 858 µs |
| Avg latency | 3.30 ms |
| p90 | 4.66 ms |
| p95 | 13.88 ms |
| p99 | 50.80 ms |
| Max latency | 197.03 ms |
| Peak VUs used | 156 of 2,000 pre-allocated |
| Threshold `p(99)<500ms` | PASS |
| Threshold `http_req_failed<1%` | PASS |
| Accepted payments | 28,810 |
| Rejected (charge already closed) | 57,629 |
| Financial correctness audit | PASS — 0 mismatches |

This is a single run against a database that was completely empty immediately before it started
(schema rebuilt, then reseeded) — not a repeat run against accumulated state. An earlier version of
this report ran twice against the same never-reset database and found sharp degradation on the
second run (p99 21ms → 472ms) caused by ~115,000 accumulated payment rows concentrated on 2 of 8
seeded `OPEN`-type charges creating `SELECT FOR UPDATE` lock-queue contention on a small hot-row
set. That finding wasn't re-verified in this run (a single clean run can't reproduce a
hot-row-over-time effect by construction) but remains a real characteristic of the pessimistic-lock
design worth testing again with a long-running, never-reset dataset if hot-row capacity planning is
needed. See `payment-gateway-evtsrc/scenarios/perf_benchmark_report.md` §6 for detail.

## How this compares to evtsrc

The full comparison, including per-stage knee/saturation analysis and the financial-correctness
audit for both systems, is in the linked report above. Summary: on the identical workload and
identical shared hardware, this repo's single-clean-run p99 (50.80ms) was roughly 23–64x lower than
evtsrc's two single-clean-run p99s (3.22s and 1.16s), and this repo's audit passed with zero
mismatches while evtsrc's audit failed in both of its runs — a single payment recorded twice
(once accepted, once flagged as a double settlement) for the same `bankReference`, reproduced in
both independently-reset runs. Neither finding is about raw per-request speed alone: evtsrc needed
1,200+ concurrent virtual users to sustain the same throughput this repo sustained with 156, and its
tail latency was still climbing when the test's ramp-down ended rather than recovering the way this
repo's did.

## A note on the test secret

The BSI sandbox escrow (`escrow_account.code = 'BSI'`, `active_environment = 'SANDBOX'`) has no
usable `client_secret` in freshly-seeded data (a `NULL`/empty secret makes the checksum trivially
guessable, so it can't be used for a real checksum-verified load test). A fresh, disposable random
secret is generated per run and written to that one sandbox row, encrypted with this app's own
`SecretCipher` (AES-256-GCM) format, and verified via a live HTTP checksum round-trip before any
load is sent. If re-running this benchmark, either reuse a real secret already configured on that
escrow, or set a new one through the admin UI (preferred) so it goes through the normal encrypted
write path.
