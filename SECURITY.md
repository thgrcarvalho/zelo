# Security Policy

Zelo is a compliance control plane — security reports are taken seriously.

## Reporting a vulnerability

**Please do not open a public issue for security vulnerabilities.**

Report privately to **security@zelocompliance.com** (or, if unavailable,
**privacidade@zelocompliance.com**). Include:

- a description of the issue and its impact,
- steps to reproduce (a proof-of-concept if you have one),
- the affected component (server, starter, dashboard) and version/commit.

You can expect an acknowledgement within **3 business days** and a status update
within **10 business days**. We'll coordinate a disclosure timeline with you and
credit you (if you wish) once a fix ships. Please give us a reasonable window to
remediate before any public disclosure.

## Scope

In scope: the control plane (`zelo-server`), the `zelo-spring-boot-starter`, the
self-service dashboard, and the deploy assets in this repo. Especially welcome:

- authentication / session / API-key handling and tenant isolation,
- the hash-chained audit log's integrity guarantees,
- webhook signing/verification,
- anything that could expose data Zelo holds (developer-account data) — Zelo stores
  **zero end-user PII** by design, so end-user data is in the integrator's database,
  not here.

Out of scope: findings against third-party infrastructure (OCI, Resend), volumetric
DoS, and reports from automated scanners without a demonstrated impact.

## Supported versions

Zelo is pre-1.0 (beta); only the latest `main` is supported. Fixes land on `main`
and deploy to the hosted service.
