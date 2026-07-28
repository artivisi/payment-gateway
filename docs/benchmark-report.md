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
  sandbox escrow's `client_secret` (previously `NULL` in seed data — see the note below) must be
  set to a real value matching `BSI_SHARED_SECRET` before running.
- **Hardware**: Apple M5, 10-core, 16GB — shared with other work at the time of both runs, not a
  dedicated benchmark host.
- **Two runs, not one, against the same never-reset database** — see "What the second run found"
  below before reading these numbers as a stable ceiling.

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

## Results (2026-07-28)

| Metric | Run 1 (`20260728140654`) | Run 2 (`20260728181811`) |
|---|---|---|
| Total requests | 86,582 | 85,853 |
| HTTP error rate | 0.00% | 0.00% |
| Dropped iterations | 42 | 772 |
| Effective throughput | 962.0 req/s | 953.6 req/s |
| Min latency | 271 µs | 272 µs |
| Median latency | 674 µs | 1.21 ms |
| Avg latency | 2.01 ms | 27.28 ms |
| p90 | 3.11 ms | 27.66 ms |
| p95 | 5.09 ms | 247.08 ms |
| p99 | 21.15 ms | **471.82 ms** |
| Max latency | 165.33 ms | 1.07 s |
| Peak VUs used | 26 of 130 pre-allocated | **662 of 716** pre-allocated |
| Threshold `p(99)<500ms` | PASS | PASS (barely) |
| Threshold `http_req_failed<1%` | PASS | PASS |
| Accepted payments | 28,953 | 28,512 |
| Rejected (charge already closed) | 57,629 | 57,341 |
| Financial correctness audit | PASS | PASS |

Both audits passed cleanly (`scenarios/verify-correctness.py --target rdbms` in the evtsrc repo):
every accepted payment has exactly one row, every rejection has zero rows, cross-checked against
the k6 outcome log independently of this app's own database.

## What the second run found

Run 1 comfortably absorbed the full ramp with headroom to spare. Run 2, against the same
never-reset database roughly 40 minutes later, degraded sharply — p99 went from 21ms to 472ms, and
the load generator needed 662 concurrent VUs instead of 26 to sustain the same throughput. This was
*not* cross-system resource contention (confirmed by re-running with the `payment-gateway-evtsrc`
stack completely torn down — the degradation persisted). The actual cause, found by querying the
database directly:

```sql
SELECT id_charge, count(*) FROM payment GROUP BY id_charge ORDER BY count(*) DESC;
--  two OPEN charges: 57,781 and 57,255 rows each
--  everything else: under 10 rows
```

The two `OPEN`-type seed charges never close (by design — see this repo's `CLAUDE.md` charge
lifecycle section), so nearly all traffic across both runs landed on the same two rows. By run 2,
those two rows had **115,045** cumulative payment rows of history behind them, and `SELECT FOR
UPDATE` row-locking under sustained concurrent load against a small, ever-growing hot set produces
worse lock queueing than a lighter approach would. This is a genuine characteristic of the
pessimistic-lock design under an adversarial workload shape (nearly all traffic through 2 of 8
charges), not benchmark noise — and it's exactly the kind of thing a single run would have missed.
A methodologically clean repeat measurement needs the database reset (or a much larger/more diverse
VA pool) between runs, which was not done here.

Time-bucketing the raw run (`payment-gateway-evtsrc/scenarios/knee-analysis.py`) locates the
degradation precisely: it is *not* a uniform slowdown. The median stays low in every stage of run 2
(0.7–1.8ms), but the 1,000→2,000 TPS window's tail detaches sharply — p95 jumps to 370ms and p99 to
535ms in that window alone, versus single-digit-to-low-double-digit ms in every other stage. That is
the signature of lock-queue depth: most requests still complete fast, but once enough concurrent
transactions are waiting on the same two `OPEN`-charge row locks, a growing tail gets stuck behind
the queue — and it drains once the ramp comes back down. Full detail, the per-stage table, and the
comparison against `payment-gateway-evtsrc`'s own (much milder) repeat-run behavior under the same
condition are in the linked report above.

## A note on the test secret

The BSI sandbox escrow (`escrow_account.code = 'BSI'`, `active_environment = 'SANDBOX'`) had a
`NULL` `client_secret` in existing seed data, which is not usable for a real checksum-verified load
test (a `NULL` secret makes the checksum trivially guessable). A fresh, disposable random secret
was generated and written to that one sandbox row for this benchmark, encrypted with this app's own
`SecretCipher` (AES-256-GCM) format, and verified via a live HTTP checksum round-trip before any
load was sent. If re-running this benchmark, either reuse a real secret already configured on that
escrow, or set a new one through the admin UI (preferred) so it goes through the normal encrypted
write path.
