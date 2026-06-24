# Implementation Plan — payment-gateway

Living tracker for the multi-bank VA gateway build. Authoritative design is `CLAUDE.md`; this file tracks **what's done vs. remaining** against it. Update checkboxes as work lands.

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked/decision needed

## Big picture

Replace Tazkia's Kafka-wired single-bank VA fleet with one self-hosted, escrow-centric gateway. The gateway never holds funds; it answers bank inquiries, records payments, and forwards webhooks to consumers.

- **Subsume** (port to in-process adapters): `bsm-makara` → `bsi` adapter, `cimb-ws` → `cimb` adapter, `aplikasi-tagihan` → CORE + Consumer API + webhook.
- **Reference only** (ArtiVisi PTB): `jasa-pelabuhan` (BCA SNAP → `maybank`), `orbit-bongkar-muat` (BNI/Mandiri, BANK_HOSTED pattern), `mandiri-routing-gateway` (dispatch pattern).
- **Key translation**: Kafka fan-out → in-process adapters + REST; system-generated VA numbers → consumer-supplied, gateway-validated; single-tenant → multi-escrow/multi-consumer; one-bill-many-banks preserved via the `Charge` aggregate (gateway-owned first-paid-cancels-siblings + shared cumulative).

## Phase progress

| Phase | Title | Status |
|---|---|---|
| 0 | Scaffold | `[ ]` |
| 1 | Core (Charge + VA lifecycle, Consumer API, webhooks) | `[ ]` |
| 2 | Adapters (bsi, cimb, maybank) | `[ ]` |
| 3 | Reconciliation | `[ ]` |
| 4 | Web admin UI | `[ ]` |

---

## Phase 0 — Scaffold

**Goal:** runnable SB4/Java25 app, schema in place, fail-loud config, escrow + consumer registries persisted.

- [ ] Maven project, Spring Boot 4 / Java 25, namespace `com.artivisi.paymentgateway`
- [ ] PostgreSQL 18 + Flyway wired; `docker-compose` for local Postgres
- [ ] Flyway baseline migration: `escrow_account`, `consumer`, `charge`, `virtual_account`, `payment`, `reconciliation_run`, `audit_event`
- [ ] `EscrowAccount` entity + admin registry (provider, credentials, hostingModel, transport/auth, endpoints, settlement account, number space, grouping tags)
- [ ] `Consumer` entity + registry (client id/secret, webhook URL)
- [ ] Fail-loud config resolution — missing/invalid escrow or consumer config throws explicitly; no defaults, no fallback adapter
- [ ] Secret handling: credentials persisted (encrypted at rest), never logged; logging filter scrubs secrets/signatures
- [ ] Testcontainers harness boots app against real Postgres
- [ ] Playwright test infra: browser/deps install, base fixture + config pointed at the booted app, CI wiring; smoke test hits the health/landing page (no admin UI yet)

**Exit:** app starts, migrations apply, an escrow + a consumer can be created and read back; missing config fails loudly; Playwright smoke test passes against the running app.

---

## Phase 1 — Core

**Goal:** full charge/VA lifecycle and consumer-facing API, independent of any real bank (adapter calls stubbed/in-memory).

### Domain & lifecycle
- [ ] `Charge` aggregate: type (OPEN/CLOSED/INSTALLMENT), amount, cumulativePaid, status, expiry, consumerReference (per-consumer idempotency)
- [ ] `VirtualAccount` as per-escrow instrument: charge + escrowAccount + consumer-supplied vaNumber + status
- [ ] VA number validation: within escrow's company-id/prefix/digit space + availability; **no generation**
- [ ] Charge create → fan out to N escrows (one vaNumber per escrow), reserve all
- [ ] Inquiry resolution: effective amount = `amount − cumulativePaid`; NOT_FOUND for cancelled/expired/unknown
- [ ] Payment application (per-charge lock + serialize):
  - [ ] CLOSED → first full payment marks PAID, cancel siblings
  - [ ] OPEN/INSTALLMENT → add to shared cumulative, reprice siblings, PAID + cancel when cumulative ≥ amount
  - [ ] Idempotency: duplicate `(va, bankReference)` rejected
  - [ ] Overpayment/double-settle → flagged discrepancy, fail loud (never silently accepted)
- [ ] Expiry handling (scheduler marks charges/VAs expired; inquiry rejects expired)

### Consumer API
- [ ] `POST /charges` (create charge + sibling VAs), `GET /charges/{id}`, cancel charge
- [ ] Consumer auth (client id/secret)
- [ ] Per-consumer idempotency on `consumerReference`

### Webhook framework
- [ ] Forward one webhook per received payment (cumulative + remaining) to consumer webhook URL
- [ ] Terminal "charge PAID" event
- [ ] Signed webhooks; retry with backoff; delivery log

**Exit:** create a charge across two stub escrows, simulate payment on one → siblings cancel, webhook fires, idempotent on replay. Covered by RestAssured + Testcontainers.

---

## Phase 2 — Adapters

**Goal:** `BankProvider` contract implemented for the three launch banks; all SELF_HOSTED (inquiry + payment notification). Suggested order **bsi → cimb → maybank** (simplest transport first; SNAP crypto last).

### Contract
- [ ] `BankProvider` interface: `hostingModel()`, SELF_HOSTED `onInquiry`/`onPaymentNotification`, BANK_HOSTED `createVa`/`cancelVa`/`onPaymentNotification`, reconciliation `pullSettlement`/`importStatement`
- [!] **Decision:** add a `reversal` op? `bsm-makara` (BSI) has time-limited payment reversal not in the current contract — resolve when starting `bsi`

### `bsi` (proprietary REST/JSON) — port of bsm-makara
- [ ] `POST` inquiry/payment endpoint, action-dispatched
- [ ] SHA1 checksum verify (`nomorPembayaran + sharedKey + tanggalTransaksi`) — keep only because the bank mandates it; don't generalize
- [ ] Map proprietary request/response (nomorPembayaran, idTransaksi, nilai, response codes 00/03/12/13/25/30/99) to core
- [ ] Reversal flow (pending contract decision)
- [ ] WireMock stub + functional test

### `cimb` (SOAP/XML) — port of cimb-ws
- [ ] Spring-WS endpoint, namespace `http://CIMB3rdParty/BillPaymentWS`, ops `CIMB3rdParty_InquiryRq/Rs`, `_PaymentRq/Rs`
- [ ] JAXB binding from `cimb.xsd`; strip response SOAP header (CIMB strictness)
- [ ] Auth via HTTPS + IP allowlist (no WS-Security)
- [ ] WireMock/SOAP stub + functional test

### `maybank` (SNAP/REST) — reference jasa-pelabuhan
- [ ] OAuth `/access-token/b2b`, RSA SHA256withRSA token signature
- [ ] HMAC-SHA512 transaction signature (`METHOD:path:token:lowerhex(sha256(body)):timestamp`); ±5min window
- [ ] `snap_external_id` (daily idempotency) + `mitra_access_token` tables
- [ ] SNAP inquiry `/v1.0/transfer-va/inquiry` + payment `/v1.0/transfer-va/payment`; partnerServiceId padding, response codes
- [ ] Test against `snap-provider-simulator`

**Exit:** each adapter answers inquiry + records payment end-to-end against its simulator/stub, driving the Phase-1 core. docker-compose runs all three.

---

## Phase 3 — Reconciliation

**Goal:** end-of-day per-escrow settlement match + recovery, for all three banks.

- [ ] `pullSettlement(escrow, period)` (transaction-list endpoint) and/or `importStatement(file)` per adapter
- [ ] Match settlement credits to payments
- [ ] Recover paid-not-notified (mark paid + forward webhook)
- [ ] Flag notified-not-settled, amount mismatch, duplicate, unmatched credit
- [ ] `ReconciliationRun` persisted; report per escrow and per institution tag
- [ ] Functional tests with seeded discrepancies

**Exit:** a recon run over a day's stub settlement detects and recovers a dropped notification and flags each discrepancy class.

---

## Phase 4 — Web admin UI

**Goal:** Thymeleaf + HTMX + Tailwind admin over the core.

- [ ] Escrow management (CRUD, credentials, number space, hosting model)
- [ ] Consumer management (CRUD, webhook URL, secret rotation)
- [ ] Charge / VA / payment browse + search
- [ ] Reconciliation dashboard (runs, discrepancies)
- [ ] Audit log view
- [ ] Frontend: native `fetch` + thin wrapper, no npm HTTP libs

**Exit:** an operator can configure an escrow + consumer and observe a charge through to settlement entirely in the UI. Playwright covers the flows.

---

## Cross-cutting (every phase)

- [ ] Functional-first tests: RestAssured + Testcontainers + Playwright; WireMock (bsi/cimb), snap-provider-simulator (maybank); docker-compose end-to-end
- [ ] Fail loud, no fallbacks — assert on every config/validation path
- [ ] Never log secrets or signatures
- [ ] DevSecOps gates: SpotBugs (0 findings), CodeQL, OWASP ZAP, OWASP Dependency-Check, SonarCloud
- [ ] Governance: no client names/credentials/endpoints/sample data in repo; neutral naming

## Open decisions

- [!] `BankProvider` reversal op (BSI has it) — Phase 2, before `bsi`
- [ ] Fuller `README.md` pass to describe charges + pay-via-any-bank (currently still VA-first outside the types section)
