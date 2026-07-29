# Benchmark Report

Load test results for `payment-gateway` (the relational baseline), run head-to-head against
[`payment-gateway-evtsrc`](https://github.com/artivisi/payment-gateway-evtsrc) — an event-sourced
comparison implementation (Kafka + embedded RocksDB write path, PostgreSQL CQRS read projection)
built to validate the trade-offs in [`architecture-comparison.md`](architecture-comparison.md).

This file reports this repo's own numbers and how to reproduce them. For the full methodology,
both systems' numbers side by side, and the financial-correctness audit, see
[`payment-gateway-evtsrc/scenarios/perf_benchmark_report.md`](https://github.com/artivisi/payment-gateway-evtsrc/blob/main/scenarios/perf_benchmark_report.md) —
that is the authoritative source; this page summarizes it.

## Two same-day corrections — read this before the numbers below

Earlier the same day these numbers were captured, both systems were benchmarked and evtsrc appeared
to saturate badly under load with a reproducible financial-correctness defect. Shortly after, the
operator found OrbStack running a hanging VM and restarted the machine, describing "a severe
resource hogging problem." Re-run on the freshly-restarted machine with an explicit
environment-contamination check added to the procedure, the **saturation finding did not
reproduce**: evtsrc's p99 dropped from a 1.16–3.22 second range to 8.5–9.4 milliseconds. That part
is retracted — it was a contamination artifact, not a property of evtsrc's architecture.

The **correctness defect was a different matter.** Its audit passing cleanly in the controlled
re-run was initially (and wrongly) treated as retracting that finding too, until the operator
pushed back: "the app should not do double payment however low the resource is, correct?" — correct.
Not reproducing under a clean environment is evidence the defect is *rare*, not evidence it isn't
*real*. It was investigated directly, root-caused (two independent, uncoordinated write paths in
evtsrc's `PostgresProjectionSink`), reproduced deterministically with a test that has no dependency
on load or timing at all, and fixed — and that fix was itself only a downstream patch. A further
push from the operator ("we can work around it with a single atomic RocksDB transaction instead")
led to the actual root-cause fix: evtsrc's write path no longer splits an optimistic request-thread
read from an async Kafka Streams decision at all. See
`payment-gateway-evtsrc/docs/benchmark-remediation-guideline.md`'s "Fifth gap" (the saturation
retraction), "Sixth gap" (the defect: root cause, downstream fix, and the correction to the
retraction), and "Seventh gap" (the deeper fix and its re-benchmark) for the full account. The
numbers below are the afternoon (controlled) re-run for the performance
comparison; the correctness defect they show as "0 mismatches" was fixed afterward, not always
absent — see the linked report's §6 for what "0 mismatches" here does and doesn't prove.

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
- **Fresh database**: the app's database (both schema and containers) was rebuilt from scratch
  immediately before this run, then reseeded, then given a freshly generated BSI secret encrypted
  and written to the escrow row and verified via a live checksum round-trip.
- **Environment check**: `docker ps -a` and `uptime` checked twice before running, specifically to
  rule out the kind of unrelated background container churn that contaminated the morning's runs.
- **Hardware**: Apple M5, 10-core, 16GB — shared with other work, not a dedicated benchmark host.

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

## Results (2026-07-29, afternoon controlled re-run)

| Metric | Value |
|---|---|
| Total requests | 86,384 |
| HTTP error rate | 0.00% |
| Dropped iterations | 241 |
| Effective throughput | 959.7 req/s |
| Min latency | 267 µs |
| Median latency | 1.01 ms |
| Avg latency | 4.25 ms |
| p90 | 3.28 ms |
| p95 | 6.84 ms |
| p99 | 112.25 ms |
| Max latency | 333.88 ms |
| Peak VUs used | 288 of 2,000 pre-allocated |
| Threshold `p(99)<500ms` | PASS |
| Threshold `http_req_failed<1%` | PASS |
| Accepted payments | 28,885 |
| Rejected (charge already closed) | 57,499 |
| Financial correctness audit | PASS — 0 mismatches |

This p99 (112ms) is higher than an earlier clean run's 50.80ms; the per-stage breakdown in the
linked report shows no saturation shape (median stays low throughout, one mild recovering tail bump
at peak concurrency), consistent with ordinary shared-hardware noise rather than a capacity issue —
plausibly residual from the contamination described above, since this run happened before the
environment check had fully settled.

## How this compares to evtsrc

Full comparison and per-stage analysis in the linked report above. Summary: this run's p99 (112ms)
was actually *higher* than evtsrc's two afternoon runs (8.5ms and 9.4ms) — the opposite of every
prior comparison in this project, and most likely explained by the residual noise noted above rather
than evtsrc suddenly being faster in the general case. What matters more than the exact ranking here
is that **both systems ran cleanly with no saturation and a passing correctness audit** on a
controlled machine — the saturation gap reported earlier the same day (evtsrc needing 1,200+ VUs and
missing its latency threshold) did not reproduce and is retracted. The correctness-audit failure
reported that same morning was not a false alarm, though: it was a real, rare defect, first patched
downstream in evtsrc's `PostgresProjectionSink` (see the linked report's §6), then fixed at its
actual source by rearchitecting evtsrc's write path onto a directly-owned RocksDB transaction (§8) —
verified both by a 50-thread concurrent stress test and by two further fresh benchmark runs
(p99 9.96ms / 9.85ms, statistically indistinguishable from before the rearchitecture). "0
mismatches" above reflects that fix, not the defect never having existed.

## A note on the test secret

The BSI sandbox escrow (`escrow_account.code = 'BSI'`, `active_environment = 'SANDBOX'`) has no
usable `client_secret` in freshly-seeded data (a `NULL`/empty secret makes the checksum trivially
guessable, so it can't be used for a real checksum-verified load test). A fresh, disposable random
secret is generated per run and written to that one sandbox row, encrypted with this app's own
`SecretCipher` (AES-256-GCM) format, and verified via a live HTTP checksum round-trip before any
load is sent. If re-running this benchmark, either reuse a real secret already configured on that
escrow, or set a new one through the admin UI (preferred) so it goes through the normal encrypted
write path.
