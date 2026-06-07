# Zelo

**Open-source, developer-first LGPD compliance toolkit for Brazilian SaaS — "Stripe for compliance."**

Zelo is an **API + Spring Boot starter** you embed in your own app to become (and
stay) LGPD-compliant: consent management, data-subject-request (DSR) orchestration,
and a **tamper-evident, hash-chained audit trail** that proves the work was done on
time.

> **Zelo is a control plane, not a data store. It holds zero end-user PII.** It stores
> only an opaque `external_id` (your app's own user id), consent records, request
> state and the audit trail. Your users' personal data never leaves your database —
> Zelo *orchestrates* operations on it via signed webhooks.

The proven analogue in the GDPR/CCPA world is Transcend / Ketch / Ethyca-Fides
(developer-first, API-driven). None of them is LGPD-native and developer-first. That
gap is the wedge.

---

## Quickstart

Self-host the whole stack — control plane, a demo integrator, and Postgres — and watch
the deletion loop run end to end:

```bash
docker compose up --build

# 1. the demo app holds a user (this is the PII Zelo never sees)
curl -XPOST localhost:8081/users -H 'content-type: application/json' \
     -d '{"external_id":"alice","name":"Alice","email":"alice@example.com"}'

# 2. ask Zelo to delete that user
curl -XPOST localhost:8081/users/alice/request-deletion

# 3. Zelo signs + fires a webhook -> the demo verifies it, erases Alice, and
#    auto-fulfills. A second later:
curl localhost:8081/users/alice                  # 404 — erased
curl localhost:8080/v1/audit/verify -H 'Authorization: demo-key'   # {"ok":true,...}
```

`scripts/e2e-loop.sh` runs exactly this flow with assertions.

---

## Add the starter to your app

```groovy
implementation 'io.github.thgrcarvalho:zelo-spring-boot-starter:0.1.0'
```

```yaml
# application.yml
zelo:
  api-url: https://zelo.example.com   # your Zelo control plane
  api-key: ${ZELO_API_KEY}            # your integrator API key
  webhook-secret: ${ZELO_WEBHOOK_SECRET}
```

Implement your erasure logic; the starter verifies the signature, calls you, and
auto-fulfills the request with your return value as proof:

```java
@Component
class Erasure {
    @ZeloWebhook("dsr.delete.requested")
    public Map<String, Object> erase(ZeloDeletionRequest request) {
        int rows = users.deleteByExternalId(request.externalId());
        return Map.of("deletedRows", rows);   // becomes the fulfillment proof
    }
}
```

That's the whole integration. Throwing from the handler tells Zelo the erasure failed,
and it retries.

---

## API surface (v1)

Auth is a static API key in the `Authorization` header. Writes are idempotent
(`Idempotency-Key`) and rate-limited.

| Method & path | Purpose |
|---|---|
| `POST /v1/subjects` | Upsert a subject by `external_id` |
| `POST /v1/purposes` · `GET /v1/purposes` | Declare/list processing purposes (with an LGPD Art. 7 legal basis) |
| `POST /v1/consents` · `GET /v1/consents` | Record consent (GRANT/WITHDRAW) · current state + full history |
| `POST /v1/requests` | Open a DELETE request → computes the deadline, fires the signed webhook |
| `GET /v1/requests/{id}` | Request state + live deadline countdown |
| `POST /v1/requests/{id}/fulfill` | Mark fulfilled, with proof |
| `GET /v1/audit` | Export the audit trail |
| `GET /v1/audit/verify` | Recompute the hash chain and report integrity |

### The moat: a tamper-evident audit trail

Every compliance event is appended to a per-integrator, hash-chained log:

```
entry_hash = SHA-256( prev_hash \n event_type \n canonical_json(payload) \n occurred_at )
```

`GET /v1/audit/verify` recomputes the whole chain and points at the first broken link —
so you can *prove the proof*. Tampering with, deleting, or reordering any historical
entry is detected. The v1 code is easy to clone; an unforgeable multi-year proof trail
is not.

---

## Architecture

A monorepo (multi-module Gradle, Java 21 / Spring Boot 3), DDD-by-layer
(`domain` / `application` / `infrastructure`):

| Module | What it is |
|---|---|
| `zelo-server` | The control plane: consent ledger, audit log, DSR engine, signed-webhook dispatcher, REST API. Runs as a service. |
| `zelo-spring-boot-starter` | Drop into *your* app. Receives + verifies signed webhooks, auto-fulfills. |
| `zelo-demo` | A sample integrator that proves the full DELETE loop. |

The consent ledger and audit log are append-only and use explicit JDBC (no ORM) to
enforce that discipline; the mutable aggregates use JPA. Webhooks are delivered through
a transactional outbox (so an event is queued in the same commit as the request it
belongs to) and signed with HMAC-SHA256. Built on these dogfooded libraries:
[`spring-boot-starter-outbox`](https://github.com/thgrcarvalho/spring-boot-starter-outbox),
[`pix-webhook-validator`](https://github.com/thgrcarvalho/pix-webhook-validator),
[`spring-boot-starter-idempotency`](https://github.com/thgrcarvalho/spring-boot-starter-idempotency),
[`spring-boot-starter-rate-limit`](https://github.com/thgrcarvalho/spring-boot-starter-rate-limit).

---

## Build & test

Requires **Java 21**. The Gradle wrapper pins **Gradle 8.13**.

```bash
./gradlew build                              # compile + unit tests (fast, no Docker)
./gradlew test -DrunIntegrationTests=true    # full suite incl. Testcontainers (needs Docker)
```

Integration tests run against a real Postgres (Testcontainers) — no H2, because the
schema uses native enums and `jsonb`.

---

## v1 scope

**In:** the complete DELETE loop — consent ledger, hash-chained audit log + verify, the
DSR engine (DELETE) with a deadline clock and overdue sweep, the signed-webhook
dispatcher, the starter, and the demo.

**Next (v1.1):** other DSR types (ACCESS, CORRECTION, PORTABILITY); breach/incident +
ANPD notification; `@PersonalData` scanning → auto data inventory/ROPA; a hosted
titular-facing portal; multi-tenancy and a managed tier.

## License

[Apache-2.0](LICENSE).
