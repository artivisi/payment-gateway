# PCI-DSS control posture

## Scope

This gateway handles **Virtual Account numbers and bank transfers**, not payment card data (PAN,
expiry, CVV, track data). VA numbers and bank account numbers are **not cardholder data**, so the
application is **out of PCI-DSS scope** as built.

We adopt PCI-DSS v4.0 **technical controls** as a security baseline and readiness measure, not because
we are in scope, and not pursuing certification artifacts (ASV scans, QSA audit, SAQ) at this stage.

**Hard rule:** do not store any payment card data (PAN/CVV) without first designing a proper
Cardholder Data Environment. The moment card data is stored, scope expands across the whole system.

## Control mapping (application-level)

| Req | Control | Implementation |
|-----|---------|----------------|
| 2.2 | No vendor defaults | No default admin credential; bootstrap ADMIN seeded from required config (`gateway.admin.bootstrap.*`), forced password change + MFA enrolment on first login. Fail-loud config throughout. |
| 3.5 | Protect stored secrets | Bank API credentials and TOTP secrets encrypted at rest (AES-256-GCM, `SecretConverter`). No card data stored. |
| 4 | Encrypt transmission | Session cookie `HttpOnly` + `SameSite=Strict`; `Secure` flag set when served over HTTPS. HSTS + security headers via Spring Security defaults. TLS termination is a deployment responsibility. |
| 6 | Secure SDLC | SpotBugs (enforced local gate, 0 findings), CodeQL, OWASP Dependency-Check, SonarCloud, OWASP ZAP (workflows under `.github/workflows`). |
| 7 | Least privilege | **Permission-based** RBAC: a fixed `Permission` vocabulary (feature → permission) enforced via `hasAuthority` in `SecurityConfig`; **roles are data** (`role` + `role_permission`), one per operator, customisable at runtime (admin Roles UI). Built-in `ADMIN` (all permissions) / `OPERATOR` (daily ops) / `AUDITOR` (view-only) seeded; new roles can be added. Built-in roles are non-deletable; a role in use cannot be deleted. |
| 8.2.1 | Unique identity | Per-operator accounts (`operator` table), no shared logins. |
| 8.3.1 | Strong cryptography for auth | Passwords hashed with bcrypt. |
| 8.3.4 | Lockout | Account locks after `max-failed-attempts` (10) for `lock-minutes` (30). `AuthEventListener`. |
| 8.3.6 | Password strength | Minimum 12 characters (`OperatorService`). |
| 8.2.8 | Idle session timeout | `server.servlet.session.timeout=15m`. |
| 8.4 / 8.5 | MFA | TOTP (RFC 6238) required for every operator; enrolment forced before first admin access (`AdminAccessGuardFilter`, `TotpService`). |
| 10 | Log all access | Authenticated actor on every `AuditEvent`; auth events logged (`AUTH_LOGIN_SUCCESS/FAILED`, `AUTH_ACCOUNT_LOCKED`, `AUTH_MFA_VERIFIED`) and operator/credential changes audited. |

### Bank-callback access control (defense in depth)

Bank → gateway callbacks cannot always carry authentication (the bank controls the client). Controls
available per provider:

- **Maybank** — SNAP RSA token + HMAC-SHA512 transaction signatures (strong).
- **BSI** — SHA1 checksum (integrity only).
- **CIMB** — none in-message; relies on the IP allowlist.

App-layer per-provider IP allowlisting (`bank_ip_rule`, `BankIpAllowlistFilter`) replaces network-layer
filtering: a source IP not in a provider's enabled rules gets HTTP 403; no enabled rule = unrestricted.
Accurate client IP behind a proxy requires `server.forward-headers-strategy` + trusting only that proxy.

## Out of application scope (deployment / process responsibilities)

- **Req 1** network segmentation / firewalls
- **Req 9** physical access
- **Req 11** quarterly ASV scans, annual penetration test, file-integrity monitoring
- **Req 12** security policy, awareness, incident response

## Deferred (Phase B)

- Password expiry/rotation policy (8.3.9).
- Operator session concurrency limits.
- Auto-trip lockout escalation / anomaly detection.
