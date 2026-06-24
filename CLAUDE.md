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

- `VirtualAccount` — `escrowAccount` + consumer-supplied `vaNumber` + `type` (`OPEN` | `CLOSED` | `INSTALLMENT`) + amount + consumer + payer + status + expiry. Type is per-VA; one escrow hosts all three simultaneously.
- `Payment` — one received transaction against a VA (one for closed; many for open/installment).
- `Consumer` — a client application (registration, academic, …): client id/secret + webhook URL. Creates VAs, receives notifications.
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

## VA number allocation

Consumers compute the number; the gateway validates it (within the escrow's company-id / prefix / digit space, and available) and registers it. **The gateway does not generate numbers.**

## Reconciliation

End-of-day, per escrow account: pull the bank's settlement (transaction-list endpoint or imported statement file), match credits to payments, recover paid-not-notified payments (mark paid + forward webhook), and flag notified-not-settled, amount mismatch, duplicate, and unmatched credit. Report per escrow and per institution.

## Principles

- **Fail loud, no fallbacks.** Missing or invalid config errors explicitly. No default bank, no fallback adapter, no silent default values anywhere.
- **Never log secrets or signatures.**
- **Never hold funds.** Settlement is bank → merchant directly (own-the-rail).
- Runtime config (escrows, consumers) is persisted and validated on use.

## Testing

Functional-first: RestAssured + Testcontainers + Playwright. Bank simulators: [snap-provider-simulator](https://github.com/artivisi/snap-provider-simulator) for SNAP (Maybank); WireMock stubs for the proprietary banks (BSI REST, CIMB SOAP). docker-compose drives end-to-end.

## Security / DevSecOps

SpotBugs (0 findings), CodeQL, OWASP ZAP DAST, OWASP Dependency-Check, SonarCloud. Frontend: native `fetch` + a thin wrapper; no npm HTTP libraries.

## Governance

Public, Apache 2.0, generic product. No client names, credentials, endpoints, or sample data in this repository. Use neutral domain naming throughout.

## Build sequence

0. **Scaffold** — SB4/Java25, PostgreSQL+Flyway, escrow + consumer registry (runtime admin), fail-loud config.
1. **Core** — VA lifecycle (create / validate / reserve / inquiry / cancel / expire) + the three VA types; Consumer API; webhook-forward framework.
2. **Adapters** — Maybank (SNAP/REST), BSI (REST/JSON), CIMB (SOAP/XML); all self-hosted (inquiry + payment notification).
3. **Reconciliation** — settlement pull/import, matching, discrepancy handling + recovery, for all three banks.
4. **Web admin UI** — escrow + consumer management, transaction list, reconciliation dashboard, audit log.
