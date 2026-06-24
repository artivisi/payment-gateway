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
| 0 | Scaffold | `[x]` |
| 1 | Core (Charge + VA lifecycle, Consumer API, webhooks) | `[x]` |
| 2 | Adapters (bsi, cimb, maybank) | `[ ]` |
| 3 | Reconciliation | `[ ]` |
| 4 | Web admin UI | `[ ]` |

---

## Phase 0 — Scaffold

**Goal:** runnable SB4/Java25 app, schema in place, fail-loud config, escrow + consumer registries persisted.

- [x] Maven project, Spring Boot 4.1 / Java 25, namespace `com.artivisi.paymentgateway`
- [x] PostgreSQL 18 + Flyway wired (needs `spring-boot-flyway` autoconfig module in SB4); `compose.yml` for local Postgres
- [x] Flyway baseline migration: `escrow_account`, `consumer`, `charge`, `virtual_account`, `payment`, `reconciliation_run`, `audit_event`
- [x] `EscrowAccount` entity + admin registry (provider, credentials, hostingModel, transport/auth, endpoints, settlement account, number space, grouping tags)
- [x] `Consumer` entity + registry (client id/secret, webhook URL)
- [x] Fail-loud config resolution — required secret key fails startup if absent; duplicate/not-found in registries throw explicitly; no defaults. (Escrow→adapter-selection fail-loud lands in Phase 2.)
- [x] Secret handling: credentials AES-256-GCM encrypted at rest (`SecretConverter`), never returned in API responses, excluded from `toString`. (Request/response log-scrubbing filter deferred to Phase 2, when adapter request logging is introduced.)
- [x] Testcontainers harness boots app against real Postgres 18
- [x] Playwright test infra: base fixture + config pointed at the booted app; smoke test hits the landing page (no admin UI yet)

**Exit:** ✅ app starts, migrations apply, an escrow + a consumer can be created and read back; missing config fails loudly; Playwright smoke test passes against the running app. (11 tests green.)

---

## Phase 1 — Core

**Goal:** full charge/VA lifecycle and consumer-facing API, independent of any real bank (adapter calls stubbed/in-memory).

### Domain & lifecycle
- [x] `Charge` aggregate: type (OPEN/CLOSED/INSTALLMENT), amount, cumulativePaid, status, expiry, consumerReference (per-consumer idempotency)
- [x] `VirtualAccount` as per-escrow instrument: charge + escrowAccount + consumer-supplied vaNumber + status
- [x] VA number validation (`NumberSpaceValidator`): prefix + digit length + numeric + availability; **no generation**
- [x] Charge create → fan out to N escrows (one vaNumber per escrow), reserve all
- [x] Inquiry resolution (`InquiryService`): effective amount = `amount − cumulativePaid`; NOT_FOUND for cancelled/expired/paid/unknown
- [x] Payment application (`PaymentApplicationService`, per-charge pessimistic lock):
  - [x] CLOSED → exact amount; first full payment marks PAID, cancels siblings
  - [x] OPEN/INSTALLMENT → shared cumulative; INSTALLMENT PAID + cancel siblings when cumulative = amount; siblings repriced live via inquiry
  - [x] Idempotency: replayed `(va, bankReference)` returns existing payment
  - [x] Overpayment / wrong amount / paying cancelled sibling → `InvalidPaymentException`, fail loud
- [~] Expiry handling: inquiry + payment reject expired charges. Scheduler to auto-mark expired = **deferred (Phase 1b)**.

### Consumer API
- [x] `POST /charges` (create charge + sibling VAs), `GET /charges/{id}`, `POST /charges/{id}/cancel`
- [x] Consumer auth (`X-Client-Id` / `X-Client-Secret`, constant-time compare)
- [x] Per-consumer idempotency on `consumerReference` (201 create vs 200 idempotent hit)

### Webhook framework (Phase 1b)
- [x] Transactional outbox: enqueue a delivery row in the payment's transaction (`WebhookService.enqueue`)
- [x] One webhook per received payment (PAYMENT_RECEIVED, with cumulative + remaining) + terminal CHARGE_PAID event
- [x] HMAC-SHA256 signed payloads (`X-Signature`, secret = consumer client secret); scheduled dispatcher (`WebhookDispatcher`) with exponential backoff + max-attempts → FAILED; delivery log (`webhook_delivery`)
- [~] Expiry scheduler (auto-mark expired charges/VAs) — still **deferred**; inquiry/payment already reject expired
- [ ] Request/response log-scrubbing filter — moves to **Phase 2** (introduced with adapter request logging)

**Exit:** ✅ charge across two escrows, payment → siblings cancel, idempotent replay, shared cumulative, fail-loud overpayment, **signed webhook delivered with retry/backoff**. 30 tests green (Testcontainers + RestAssured + JDK HttpServer receiver).

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
