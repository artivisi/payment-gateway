# CLAUDE.md — payment-gateway

Multi-bank Virtual Account gateway. Read this before changing code.

## What this is

A unified VA collection API for Indonesian institutions, with per-bank adapters behind it. Self-hosted; the operator holds the bank relationships and settlement accounts. The gateway never holds funds.

## Stack

Spring Boot 4 · Java 25 · PostgreSQL 18 + Flyway · Spring WebClient · Thymeleaf + HTMX + Tailwind (admin UI). Namespace `com.artivisi.paymentgateway`.

## Domain model (escrow-centric)

`EscrowAccount` is the core unit — one bank biller. It carries everything structural:

- bank/provider (selects the adapter)
- API credentials: client id, client secret, keys, mTLS
- `hostingModel`: `SELF_HOSTED` (Model 1) | `BANK_HOSTED` (Model 2)
- transport + auth: REST/JSON | SOAP/XML; SNAP | proprietary
- endpoints (sandbox / prod)
- settlement: physical bank account
- number space: company id + prefix + available digits
- grouping tags: merchant, institution — labels for reporting/attribution, **not** aggregates

Other entities:

- `Charge` — the unit of money owed, created by a `Consumer`. Carries `payer`, `type` (`OPEN` | `CLOSED` | `INSTALLMENT`), `amount` (target), `cumulativePaid`, `status`, `expiresAt` (**soft** — see below), and `consumerReference` (the consumer's own bill id; unique per consumer — idempotency key). A charge is payable through **one or more** sibling `VirtualAccount`s across different escrows (pay-via-any-bank). Type and amount live here, not on the VA, because they describe one debt regardless of which bank rail settles it. One escrow hosts all three charge types simultaneously.
- `VirtualAccount` — one bank payment instrument for a charge: `charge` + `escrowAccount` (selects the adapter) + consumer-supplied `vaNumber` (validated within the escrow's number space) + `status`. The effective amount answered on inquiry is `charge.amount − charge.cumulativePaid`. A single-bank charge is just a charge with one VA.
- `Payment` — one received transaction, against a `VirtualAccount` (and its `charge`). One settles a CLOSED charge; many accumulate for OPEN/INSTALLMENT.
- `Consumer` — a client application (registration, academic, …): client id/secret + webhook URL. Creates charges, receives notifications.
- `ReconciliationRun`, `AuditEvent`.

Settlement is per escrow account (the bank account sits on the escrow). Per-institution settlement = separate escrow accounts grouped by the `institution` tag. There is no first-class `Merchant`/`Institution`/`BankConnection` aggregate — those are grouping tags only.

## BankProvider adapter contract

```
hostingModel(): SELF_HOSTED | BANK_HOSTED
transport+codec (REST/JSON | SOAP/XML), auth/signing (SNAP | proprietary)

SELF_HOSTED (Model 1) — gateway is VA source of truth:
  onInquiry(vaNumber) -> BillDetails | NOT_FOUND      // bank -> gateway
  onPaymentNotification(payload) -> ack               // bank -> gateway

BANK_HOSTED (Model 2) — bank is VA source of truth:
  createVa(virtualAccount) -> ack | rejected          // gateway -> bank
  cancelVa(virtualAccount)                             // gateway -> bank
  onPaymentNotification(payload) -> ack               // bank -> gateway

reconciliation: pullSettlement(escrow, period) | importStatement(file)
```

Launch adapters (all `SELF_HOSTED`): `maybank` (SNAP/REST), `bsi` (proprietary REST/JSON), `cimb` (proprietary SOAP/XML). The `BANK_HOSTED` path (e.g. BNI) is part of the interface; implement it when a bank-hosted deal lands.

`Payment` creation branches on the escrow's `hostingModel`:
- `SELF_HOSTED` → validate space + local availability → reserve locally (gateway answers inquiries).
- `BANK_HOSTED` → validate space → `createVa` at bank → reserve on success (bank is authoritative; gateway keeps a VA↔payment mirror for notification matching + reconciliation).

## Charge lifecycle & pay-via-any-bank

A `Charge` is payable through 1..N sibling `VirtualAccount`s, one per target escrow. The gateway owns the single-debt invariant across siblings so consumers never reimplement double-payment prevention or shared-balance accounting:

- **CLOSED** — the first full payment marks the charge `PAID`; all sibling VAs are cancelled.
- **OPEN / INSTALLMENT** — each payment adds to `cumulativePaid` (**shared across siblings** — a partial payment at one bank lowers the remaining due at every other); siblings are repriced so their next inquiry returns `amount − cumulativePaid`; when `cumulativePaid ≥ amount` the charge is `PAID` and the siblings cancelled.
- Cancelling/repricing a sibling is a **local state change** for `SELF_HOSTED` escrows (the gateway answers inquiries) — no bank call, no cross-bank race in the common path. A cancelled VA's next inquiry returns `NOT_FOUND`.
- Payment handling locks per charge and serializes. Residual race: two `SELF_HOSTED` banks settling a CLOSED charge near-simultaneously can both clear before either notification is processed — the second is flagged as an overpayment/duplicate discrepancy for out-of-band refund, **never silently accepted** (fail loud).
- `BANK_HOSTED` siblings make a cancel race a bank-side payment in flight; resolved through reconciliation recovery (paid-not-notified / notified-not-settled). Defer until a bank-hosted deal lands.

One webhook is emitted per received payment (carrying cumulative + remaining); the charge reaching `PAID` is a terminal event.

## Expiry is soft

`expiresAt` is enforced **at read time**: inquiry and payment both refuse a charge past its date. The
sweep retires the charge's VAs — a VA number is scarce and must be free for the next bill — but
**never changes the charge's status**. The debt is still owed, and a charge whose status was flipped
to EXPIRED could never afterwards be observed as "eventually paid", which silently biases collection
analysis against exactly the aged bills such analysis exists to measure. Extending a deadline is
therefore just moving the date; there is no status to unwind.

This also matches the systems being replaced: the self-hosted adapters derive expiry at read time and
leave the bill's own state alone, while a `BANK_HOSTED` bank (BNI) is *sent* the expiry at VA creation
and enforces it itself — so the field is required there regardless of our own policy.

## VA number allocation

Consumers compute the number; the gateway validates it (within the escrow's company-id / prefix / digit space, and available) and registers it. A charge supplies **one `vaNumber` per target escrow**. **The gateway does not generate numbers.**

**VA numbers are reusable.** A number may back several `VirtualAccount`s over time (one per successive charge), but at most **one ACTIVE** per escrow+number (enforced by `uq_va_escrow_number_active`); the rest are retired (PAID/CANCELLED/EXPIRED). Every lookup is therefore **generation-aware**: inquiry resolves the ACTIVE generation; payment/reversal/reconciliation prefer the ACTIVE generation and check idempotency across all generations of the number. Never assume a `(escrow, vaNumber)` maps to a single VA row.

## Reconciliation

End-of-day, per escrow account: ingest the bank's settlement, match credits to payments, recover paid-not-notified payments (mark paid + forward webhook), and flag notified-not-settled, amount mismatch, duplicate, and unmatched credit. Report per escrow and per institution.

**Settlement input is a downloaded file, not an API.** Indonesian banks generally don't expose a settlement pull endpoint — ops download a CSV from the bank's cash-management portal (or a PDF statement). The primary path is the **CSV import** (`/reconciliations/import`); per-bank CSV column/format mapping is the open work. A `pullSettlement` REST API is bank-dependent and rare — implement only if a specific bank offers it (or SFTP host-to-host file drop). Prefer CSV over parsing PDFs in-app.

## Principles

- **Fail loud, no fallbacks.** Missing or invalid config errors explicitly. No default bank, no fallback adapter, no silent default values anywhere.
- **Never log secrets or signatures.**
- **Never hold funds.** Settlement is bank → merchant directly (own-the-rail).
- Runtime config (escrows, consumers) is persisted and validated on use.

## Testing

Functional-first: RestAssured + Testcontainers + Playwright. Bank simulators: [snap-provider-simulator](https://github.com/artivisi/snap-provider-simulator) for SNAP (Maybank); WireMock stubs for the proprietary banks (BSI REST, CIMB SOAP). docker-compose drives end-to-end.

## Security / DevSecOps

SpotBugs (0 findings), CodeQL, OWASP ZAP DAST, OWASP Dependency-Check, SonarCloud. Frontend: native `fetch` + a thin wrapper; no npm HTTP libraries.

**Admin auth (PCI control baseline — not in PCI scope, no card data; see `docs/security/pci-dss.md`).** Spring Security gates `/admin`: per-operator accounts, bcrypt + min-12 passwords, lockout, 15-min idle session, forced password change + **TOTP MFA** on first login, fail-loud bootstrap admin (no default credential). Every `AuditEvent` carries the authenticated actor. Authorization is **permission-based**: a fixed `Permission` enum (feature → permission, checked via `hasAuthority`), with **data-driven roles** (`role`/`role_permission`, one per operator) editable at runtime via the admin Roles UI — built-in `ADMIN`/`OPERATOR`/`AUDITOR` seeded, custom roles addable.

**Bank-callback access control.** Maybank has SNAP signatures; BSI a checksum; CIMB nothing in-message. App-layer **per-provider IP allowlist** (`bank_ip_rule`, admin-managed) gates `/api/bank/*` + `/ws/cimb/*` — source IP not allowlisted → 403, no rule = unrestricted. Needs `server.forward-headers-strategy` + a trusted proxy behind a load balancer.

## Governance

Public, Apache 2.0, generic product. No client names, credentials, endpoints, or sample data in this repository. Use neutral domain naming throughout.

## Build sequence

0. **Scaffold** — SB4/Java25, PostgreSQL+Flyway, escrow + consumer registry (runtime admin), fail-loud config.
1. **Core** — `Charge` + sibling-VA lifecycle (create / validate / reserve / inquiry / cancel / expire), the three charge types, shared-cumulative accounting + first-paid-cancels-siblings; Consumer API; webhook-forward framework.
2. **Adapters** — Maybank (SNAP/REST), BSI (REST/JSON), CIMB (SOAP/XML); all self-hosted (inquiry + payment notification).
3. **Reconciliation** — settlement pull/import, matching, discrepancy handling + recovery, for all three banks.
4. **Web admin UI** — escrow + consumer management, transaction list, reconciliation dashboard, audit log.
