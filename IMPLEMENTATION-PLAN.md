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
| 2 | Adapters (bsi, cimb, maybank) | `[x]` |
| 3 | Reconciliation | `[~]` |
| 4 | Web admin UI | `[x]` |

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
- [x] **Per-consumer delivery isolation (slow-endpoint hardening).** Claim → send → record split: the HTTP send (`WebhookSender`) runs outside any DB tx on a **virtual thread**, so a slow endpoint never holds a Hikari connection. Dispatcher applies a **per-consumer bulkhead** (semaphore, size `per-consumer-concurrency`) + fair claim cap (`claimBatch`) so one consumer's backlog can't starve others; `SENDING` state + stale-reclaim guards against re-pick/crash. **Manual ops controls** (no auto circuit-breaker): per-consumer `webhookSuspended` kill-switch + `replayFailed`/`replayDelivery` requeue; terminal FAILED → `log.error` alert + `WEBHOOK_DELIVERY_FAILED` audit. **Admin observability** (no log-diving): per-consumer FAILED count on the consumer list + a **webhook-delivery analysis view** (`/admin/webhooks`, status filter, charge/event/attempts/HTTP/last-error detail, per-row + per-consumer replay). Config: `gateway.webhook.{per-consumer-concurrency,batch-size,stale-sending-seconds}`. `WebhookBulkheadTest` (7 tests).
- [~] Expiry scheduler (auto-mark expired charges/VAs) — still **deferred**; inquiry/payment already reject expired
- [ ] Request/response log-scrubbing filter — moves to **Phase 2** (introduced with adapter request logging)

**Exit:** ✅ charge across two escrows, payment → siblings cancel, idempotent replay, shared cumulative, fail-loud overpayment, **signed webhook delivered with retry/backoff**. 30 tests green (Testcontainers + RestAssured + JDK HttpServer receiver).

---

## Phase 2 — Adapters

**Goal:** `BankProvider` contract implemented for the three launch banks; all SELF_HOSTED (inquiry + payment notification). Suggested order **bsi → cimb → maybank** (simplest transport first; SNAP crypto last).

### Contract
- [x] SELF_HOSTED adapters are inbound endpoints (bank → gateway) that verify the bank's auth, resolve the escrow from the VA number (`EscrowResolver`), and call core `InquiryService` / `PaymentApplicationService` (+ `reverse`). **Finding (bsi + cimb done):** the shared contract is those core services, not a polymorphic interface — bsi (REST/JSON) and cimb (SOAP/XML) have genuinely different transports/message types, so a `BankProvider` Java interface for inbound adds no value and is skipped. The interface becomes meaningful for **BANK_HOSTED outbound** (`createVa`/`cancelVa`) + **reconciliation** (`pullSettlement`/`importStatement`); extract it there, with those phases.
- **Decision (resolved):** reversal **is** part of the SELF_HOSTED contract — BSI sends reversal messages, so the gateway must handle them. Implement inquiry + payment first, then reversal (revert cumulative, re-open charge + reactivate siblings settled by the reversed payment; fail loud on cancelled charges / out-of-window).

### `bsi` (proprietary REST/JSON) — port of bsm-makara
- [x] `POST /api/bank/bsi` endpoint, action-dispatched (inquiry | payment)
- [x] Escrow resolution from VA number (`EscrowResolver`, by provider + number space)
- [x] SHA1 checksum verify (`nomorPembayaran + sharedKey + tanggalTransaksi`, sharedKey = escrow secret) — kept only because the bank mandates it
- [x] Map proprietary request/response to core (`InquiryService`/`PaymentApplicationService`); response codes 00/03/12/13/25
- [x] Reversal flow — `PaymentApplicationService.reverse` (window-bounded via `gateway.reversal.window-minutes`; subtracts shared cumulative, re-opens charge + reactivates siblings settled by the reversed payment; idempotent; emits PAYMENT_REVERSED webhook). BSI `reversal` action mapped.
- [x] Functional test (RestAssured; bank→gateway, so no WireMock needed for inbound). 7 adapter + 4 lifecycle reversal tests green
- **`bsi` adapter complete** (inquiry + payment + reversal).

### `cimb` (SOAP/XML) — port of cimb-ws
- [x] Spring-WS endpoint (MessageDispatcherServlet at `/ws/cimb/*`, coexists with the REST DispatcherServlet), namespace `http://CIMB3rdParty/BillPaymentWS`, ops `CIMB3rdParty_InquiryRq/Rs`, `_PaymentRq/Rs`
- [x] Hand-written JAXB message classes (children unqualified per CIMB's format); default payload processors bind them — no marshaller bean needed in spring-ws 5. (Published WSDL/XSD = follow-up.)
- [x] Auth via HTTPS + IP allowlist at the network layer (no in-message signature). Response SOAP-header stripping not needed (Spring-WS responses carry no header by default).
- [x] Functional test: SOAP envelopes via RestAssured; response codes 00/16/38. 4 tests green
- No reversal (CIMB protocol has none).
- **`cimb` adapter complete** (inquiry + payment).

### `maybank` (SNAP/REST) — reference jasa-pelabuhan
- **Target SNAP 1.0.2** (Sept 2024). Spec confirmed from the official BI/ASPI PDFs (Standar Teknis & Keamanan + Standar Data; in user's Downloads — do NOT commit to this public repo). Signature/header model is **identical** to the PTB BCA code → `SnapSignatureHelper` is directly reusable. The only v1.0.1→1.0.2 changes (none breaking): "Private Key" wording removed from key-exchange sections; access-token body field `granttype`→`grantType` (PTB already correct); `X-EXTERNAL-ID` description Numeric→**Alphanumeric** (B2B). Verify Maybank's per-bank profile (sandbox URLs, partnerServiceId padding) against their sandbox.
- Confirmed string-to-sign (build against these exactly):
  - Token: `X-SIGNATURE = SHA256withRSA(privateKey, clientId + "|" + X-TIMESTAMP)`; `POST {version}/access-token/b2b`, body `grantType=client_credentials`, token TTL 900s.
  - Transaction (symmetric): `X-SIGNATURE = HMAC-SHA512(clientSecret, HTTPMethod + ":" + relativeUrl + ":" + accessToken + ":" + lowercaseHex(SHA-256(minify(body))) + ":" + X-TIMESTAMP)`. Headers: Authorization Bearer, X-PARTNER-ID, X-EXTERNAL-ID (alphanumeric, unique/day), CHANNEL-ID.
  - Nuance: **minify** (strip whitespace) the body before SHA-256 on outbound signing; on inbound verification hash the raw received body. Empty body → empty string. X-TIMESTAMP `yyyy-MM-ddTHH:mm:ss.SSSTZD` (spec example omits millis — follow the bank's sandbox).
- VA message schemas (transfer-va inquiry/payment fields, response codes 200xx/404xx) live in the **Standar Data & Spesifikasi Teknis** PDF (§4.2.2.7) — mine that when building the inbound endpoints.
- Derived SNAP index committed at `docs/snap/snap-1.0.2.json` (stable ids, our interpretation, source checksums + ASPI URLs). Tag SNAP code with `@SnapSpec("snap.…")` for spec traceability; `SnapSpecIndexTest` guards the index.
- [x] OAuth `/access-token/b2b`, RSA SHA256withRSA token signature validation (`SnapSignatureHelper.verifyToken`, `MaybankController.token`); `SnapTokenService` issues 900s bearer tokens (`snap_access_token`)
- [x] HMAC-SHA512 transaction signature validation over the raw body (`SnapRequestValidator`); escrow resolved from the bearer token
- [x] Daily `X-EXTERNAL-ID` idempotency (`snap_external_id`, V3); `snap_access_token` table
- [x] SNAP inquiry + payment endpoints; response codes 2007300/2002400/2002500/4042412/4012400/4092400
- [~] `snap-provider-simulator` e2e — not used; the bank is the caller, so the test signs (RSA+HMAC) and calls our endpoints directly. Simulator/docker-compose e2e = follow-up.
- **`maybank` adapter complete.** `@SnapSpec` traceability is enforced — `SnapSpecIndexTest` scans the code and fails if any tag references an id missing from `docs/snap/snap-1.0.2.json`.
- Follow-ups: ±5min X-TIMESTAMP window check; partnerServiceId padding nuance; `snap-provider-simulator` e2e.

**Exit:** each adapter answers inquiry + records payment end-to-end against its simulator/stub, driving the Phase-1 core. docker-compose runs all three.

---

## Phase 3 — Reconciliation

**Goal:** end-of-day per-escrow settlement match + recovery, for all three banks.

- [x] Match settlement credits to payments (`ReconciliationService`, escrow + period scoped)
- [x] Recover paid-not-notified (`apply` → payment created + webhook forwarded), flagged `PAID_NOT_NOTIFIED_RECOVERED` (or `RECOVERY_FAILED`)
- [x] Flag notified-not-settled, amount mismatch, duplicate, unmatched credit (`DiscrepancyType`)
- [x] `ReconciliationRun` (+ matched/recovered/discrepancy counts) and `ReconciliationDiscrepancy` persisted (V4); admin trigger `POST /api/escrow-accounts/{code}/reconciliations` (JSON credits) **and** `/reconciliations/import` (multipart settlement CSV → `SettlementCsvParser`) — realizes `importStatement(file)`
- [x] Test-data rig: `src/test/resources/testdata/*.csv` fixtures (escrow/consumer/charge/payment + settlement-sample + expected-discrepancies) loaded via `CsvFixtures`; `ReconciliationCsvImportTest` drives the full scenario through the upload endpoint
- [~] `pullSettlement` outbound per-bank (transaction-list endpoint + WireMock) — deferred; the engine is bank-agnostic and fed by imported credits for now. This is where the **BANK_HOSTED/outbound `BankProvider` interface** finally gets extracted.
- [~] Report per **institution tag** — per-escrow run persisted; cross-escrow per-institution aggregation = follow-up
- [x] Functional tests: all six outcomes classified, recovery + webhook, clean run, HTTP endpoint. 4 tests

**Exit:** ✅ a recon run over a day's imported settlement detects + recovers a dropped notification and flags each discrepancy class. 61 tests green. (Outbound pull + per-institution report remain.)

---

## Phase 4 — Web admin UI

**Goal:** Thymeleaf + HTMX + Tailwind admin over the core.

- [x] Brand + shell: Tailwind v4 `@theme` (ArtiVisi primary #2e3192 / accent #58c034), standalone-CLI build → `static/css/app.css` (binary gitignored; rebuild cmd in `src/main/frontend/app.css`); logos + branded nav; dashboard with counts. `/` → `/admin`.
- [~] Escrow management — list + create form done; edit/delete + detail/credential view = follow-up
- [~] Consumer management — list + create form done; secret rotation = follow-up
- [x] Charge / VA / payment browse — charge list + detail (sibling VAs + payments), payment list. (Free-text search = follow-up.)
- [x] Reconciliation dashboard — runs list + run detail with the discrepancy ledger.
- [x] Audit log view — read-only list, now populated: `AuditService` writes ESCROW/CONSUMER/CHARGE created/cancelled, PAYMENT applied/reversed, RECONCILIATION_RUN (no secrets in detail).
- [x] Frontend self-hosted, no CDN (CSP-friendly): htmx vendored. **Alpine.js not used** (htmx + server-render sufficed); if added later, use the **CSP build** (`@alpinejs/csp`) per project guidance. `cookie`-only session tracking (no jsessionid in URLs).
- [x] All browse screens use **fetch-join queries** (works with `open-in-view=false`).
- [x] Playwright: dashboard on brand, create-consumer through the form, and all browse sections render.

**Exit:** ✅ operator can configure escrows + consumers and observe charges → payments → reconciliation in the branded UI. 63 tests green. Follow-ups: escrow/consumer **edit/delete**, list **search/pagination**, and **writing** audit events.

---

## Cross-cutting (every phase)

- [ ] Functional-first tests: RestAssured + Testcontainers + Playwright; WireMock (bsi/cimb), snap-provider-simulator (maybank); docker-compose end-to-end
- [ ] Fail loud, no fallbacks — assert on every config/validation path
- [ ] Never log secrets or signatures
- [~] DevSecOps gates: **SpotBugs** ✅ (verify gate, 0 findings) · **CodeQL** ✅ (`.github/workflows/codeql.yml`) · **OWASP Dependency-Check** ✅ (`dependency-check.yml`, weekly, needs `NVD_API_KEY` secret) · **CI** ✅ (`ci.yml`: JDK 25, `mvn verify` = tests + SpotBugs). Remaining: **SonarCloud** + **OWASP ZAP** (need external project/token + a running target).
- [ ] Governance: no client names/credentials/endpoints/sample data in repo; neutral naming

## Open decisions

- [!] `BankProvider` reversal op (BSI has it) — Phase 2, before `bsi`
- [ ] Fuller `README.md` pass to describe charges + pay-via-any-bank (currently still VA-first outside the types section)
