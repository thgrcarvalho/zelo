# Zelo

**Open-source, developer-first LGPD compliance toolkit for Brazilian SaaS — "Stripe for compliance."**

Zelo is an **API + Spring Boot starter** you embed in your own app to become (and stay)
LGPD-compliant: consent management, data-subject-request (DSR) orchestration, and a
**tamper-evident, hash-chained audit trail** that proves the work was done on time.

> **Zelo is a control plane, not a data store. It stores zero end-user PII.** It holds
> only an opaque `external_id` (your app's own user id), consent records, request state
> and the audit trail. Your users' personal data never leaves your database — Zelo
> *orchestrates* operations on it via signed webhooks.

## Why

The proven analogue in the GDPR/CCPA world is Transcend / Ketch / Ethyca-Fides
(developer-first, API-driven). None of them is LGPD-native and developer-first. Zelo is.

## Modules

| Module | What it is |
|---|---|
| `zelo-server` | The control plane: consent ledger, audit log, DSR engine, webhook dispatcher, REST API. Runs as a service. |
| `zelo-spring-boot-starter` | Drop into *your* app. Receives + verifies signed webhooks, auto-fulfills requests. |
| `zelo-demo` | A sample integrator app that proves the full DELETE loop end to end. |

## Build

```bash
./gradlew build                              # compile + unit tests (fast, no Docker)
./gradlew test -DrunIntegrationTests=true    # full suite incl. Testcontainers (needs Docker)
```

Requires **Java 21**. The Gradle wrapper pins **Gradle 8.13**.

## License

[Apache-2.0](LICENSE).
