# Zelo — v1 Plan

> **What this file is.** A self-contained build plan for Zelo v1. It assumes no prior
> conversation context. Read it top to bottom before scaffolding. The companion goals
> for this project are two-in-one: (1) ship a validatable open-source product, and
> (2) use deploying it as a hands-on DevOps learning exercise on Oracle OCI's free tier.

---

## 1. The product, in one paragraph

Zelo is an **open-source, developer-first LGPD compliance toolkit** for Brazilian SaaS
apps — think "Stripe for compliance." Instead of selling a dashboard to business owners,
it sells an **API + Spring Boot starter** that other developers embed in their apps to
become (and stay) LGPD-compliant: consent management, data-subject-request (DSR)
orchestration, and a tamper-evident audit trail that proves the work was done on time.
The proven analogue in the GDPR/CCPA world is Transcend / Ketch / Ethyca-Fides
(developer-first, API-driven, venture-backed). None of them is LGPD-native and
developer-first — that gap is the wedge.

## 2. Hard invariants (do not violate these)

These are the load-bearing design decisions. Everything else is negotiable.

1. **Zelo is a control plane, NOT a data store. It stores ZERO end-user PII.** It holds
   only an opaque `external_id` (the integrator app's own user id), consent records,
   request state, and the audit trail. The actual personal data stays in the
   integrator's database. Zelo *orchestrates* operations on it via signed webhooks.
   This avoids making Zelo a honeypot and keeps Zelo's own LGPD liability minimal.
2. **The consent ledger and audit log are append-only.** Withdrawing consent writes a
   new event; it never mutates or deletes a prior one. Current state = the latest event.
3. **The audit log is hash-chained (tamper-evident).** This is the moat. The v1 code is
   easy to clone; an unforgeable multi-year proof trail is not. Build it well.
4. **Open-core.** The engine is fully open source and self-hostable from day one
   (`docker compose up`). A hosted/managed tier comes later as paid convenience, not as
   a gate on the core. Open source is also the trust + distribution + validation engine.

## 3. Architecture

Two deliverables in one repo (monorepo, multi-module Gradle build):

- **`zelo-server`** — the control plane. Spring Boot 3 + PostgreSQL. Stores consent +
  request state + audit log, fires signed webhooks, exposes the REST API. *This is the
  service that runs on the OCI box.*
- **`zelo-spring-boot-starter`** — what an integrator drops into *their* app. Talks to
  the server, receives + verifies signed webhooks, auto-fulfills requests.
- **`zelo-demo`** — a tiny sample integrator app that uses the starter. Proves the full
  loop end-to-end, doubles as living documentation, and is the second service you deploy
  alongside `zelo-server` on OCI.

### The core loop (a deletion request, end to end)
1. An end-user requests deletion → integrator app calls `POST /v1/requests {type: DELETE}`.
2. Zelo creates the request, computes the legal deadline, status `RECEIVED`, writes an
   audit entry.
3. Zelo fires a **signed (HMAC-SHA256) webhook** `dsr.delete.requested` to the
   integrator's endpoint (via the outbox/retry mechanism).
4. The starter validates the signature, dispatches to the dev's `@ZeloWebhook` handler,
   which runs *the app's own* erasure logic in *the app's own* DB.
5. On handler return, the starter calls `POST /v1/requests/{id}/fulfill` with proof.
6. Zelo records fulfillment + timestamp → audit proof. If the app goes silent past the
   deadline, a sweep job flags the request `OVERDUE`.

### Repo layout (DDD hexagonal-lite — domain / application / infrastructure)
Per the user's standard Java layering, **never a flat root package**. Each module:
```
src/main/java/<base>/zelo/
  domain/          # entities, value objects, domain services, ports (interfaces)
  application/     # use-case services orchestrating the domain
  infrastructure/  # REST controllers, persistence adapters, webhook dispatcher, config
```

## 4. Tech stack

- **Java 21**, **Spring Boot 3.x**, **Gradle** (match the user's existing repo convention,
  e.g. `kdtree4j`).
- **PostgreSQL 16** + **Flyway** migrations (sequential `V1__`, `V2__`, … — never edit an
  applied migration).
- **Spring Data JDBC / JdbcTemplate** for the **consent ledger + audit log** — explicit
  SQL enforces the append-only + hash-chain discipline (no accidental ORM mutation). JPA
  is acceptable for the mutable aggregates (`dsr_requests`, `api_keys`) if preferred.
- **Testcontainers** for integration tests (real Postgres, no H2).
- **License: Apache-2.0** (patent grant matters for a library companies embed).

### Dogfood the user's own published libraries
This both reduces build effort and validates those libraries in a real product:
| Need | Reuse |
|---|---|
| Reliable outbound webhook delivery | `spring-boot-starter-outbox` |
| HMAC-signed webhook generation/validation | `pix-webhook-validator` (HMAC patterns) |
| Idempotent write endpoints | `spring-boot-starter-idempotency` |
| API rate limiting | `spring-boot-starter-rate-limit` |

## 5. Data model (starting sketch — refine as you build)

> No PII anywhere. `external_id` is the only link back to a real person, and it lives in
> the integrator's DB.

- **api_keys** — `id`, `key_hash`, `name`, `webhook_url`, `webhook_secret`, `created_at`.
  One key = one integrator (forward-compatible with multi-tenancy; key scopes all rows).
- **subjects** — `id`, `api_key_id` (FK), `external_id`, `created_at`. Unique
  `(api_key_id, external_id)`. **No PII.**
- **purposes** — `id`, `api_key_id`, `key` (e.g. `billing`), `description`,
  `legal_basis` (enum: the 10 LGPD Art. 7 bases — CONSENT, CONTRACT, LEGAL_OBLIGATION,
  …), `created_at`.
- **consent_events** *(append-only)* — `id`, `subject_id`, `purpose_id`,
  `action` (GRANT | WITHDRAW), `source`, `occurred_at`, `metadata` (jsonb), `created_at`.
- **dsr_requests** — `id`, `subject_id`, `type` (DELETE in v1), `status`
  (RECEIVED | DISPATCHED | FULFILLED | OVERDUE), `deadline_at`, `created_at`,
  `dispatched_at`, `fulfilled_at`, `fulfillment_proof` (jsonb).
- **audit_log** *(append-only, hash-chained)* — `id` (bigserial, ordering), `api_key_id`,
  `event_type`, `payload` (jsonb), `occurred_at`, `prev_hash`, `entry_hash`.

### Hash-chain rule (implement consistently)
```
entry_hash = SHA-256( prev_hash || event_type || canonical_json(payload) || occurred_at )
```
- Genesis entry: `prev_hash` = 64 zero chars.
- `canonical_json` = deterministic key ordering (so the hash is reproducible).
- Provide `GET /v1/audit/verify` that recomputes the whole chain and returns OK / the
  first broken link. ("Prove the proof.")

## 6. API surface (v1)

```
POST /v1/subjects               { external_id }                  -> upsert subject
POST /v1/purposes               { key, description, legal_basis }
GET  /v1/purposes
POST /v1/consents               { external_id, purpose_key, action, source }
GET  /v1/consents?subject=&purpose=                              -> current state + history
POST /v1/requests               { external_id, type: "DELETE" }  -> create DSR + fire webhook
GET  /v1/requests/{id}                                           -> state + deadline countdown
POST /v1/requests/{id}/fulfill  { proof }                        -> mark FULFILLED
GET  /v1/audit?from=&to=                                         -> export trail
GET  /v1/audit/verify                                            -> recompute chain integrity
```
Auth: static API key in `Authorization` header (v1). Idempotency + rate-limit on writes.

### Webhook contract (Zelo → integrator)
- Event: `dsr.delete.requested`, body `{ requestId, externalId, deadline }`.
- Header: `X-Zelo-Signature: sha256=<hmac>` over the raw body using the integrator's
  `webhook_secret`. Retried via outbox with backoff until the integrator returns 2xx or
  calls `/fulfill`.

## 7. v1 scope

**IN** — the complete DELETE loop and nothing more:
- Consent ledger (record + query current-state-with-history).
- Hash-chained audit log + verify endpoint.
- DSR engine, **DELETE only**, with deadline clock + OVERDUE sweep.
- Signed webhook dispatcher (outbox + HMAC).
- `zelo-spring-boot-starter` (client + `@ZeloWebhook` handler + auto-fulfill).
- `zelo-demo` integrator app proving the loop end-to-end.

**OUT** — real, but deferred (do not build in v1):
- Other DSR types (ACCESS, CORRECTION, PORTABILITY).
- Breach/incident workflow + ANPD notification templates.
- `@PersonalData` annotation scanning → auto data-inventory/ROPA *(strong v1.1)*.
- Hosted titular-facing portal *(strong v1.1)*.
- Multi-tenancy, dashboard, billing, auth beyond static API key, pt-BR/en docs polish.

## 8. Build order (each milestone is independently demoable)

- **M0 — Skeleton.** Multi-module Gradle (Java 21 / Spring Boot 3), docker-compose
  Postgres, Flyway, Testcontainers, DDD layout, health endpoint. *Verify CI is green.*
- **M1 — Consent ledger + hash-chained audit log (the moat), running locally.** Build
  this FIRST — everything hangs off the audit trail's shape. Integration tests with
  Testcontainers, including a chain-tampering test that `/audit/verify` must catch.
- **M2 — DSR engine (DELETE).** State machine, deadline computation, fulfill endpoint,
  audit entry per transition.
- **M3 — Signed webhook dispatcher.** Outbox table, HMAC signing, retry/backoff,
  dispatch on DSR creation.
- **M4 — `zelo-spring-boot-starter`.** Client + `@ZeloWebhook` handler (signature
  pre-validated) + auto-fulfill. Publish to `mavenLocal` for the demo.
- **M5 — `zelo-demo` integrator app.** Fake user table; on `dsr.delete.requested` it
  deletes the user and reports back. **Full loop green = v1 thesis proven.**
- **M6 — Overdue sweep.** Scheduled job marks past-deadline requests OVERDUE + audit
  entry. (Becomes the Kubernetes CronJob later.)
- **M7 — Containerize + deploy to OCI (DevOps Phase 1, manual).** Dockerfiles +
  docker-compose for the full stack; deploy to the OCI A1 instance behind nginx +
  Let's Encrypt + systemd. Feel everything Railway hides.
- **M8 — k3s migration (DevOps Phase 2, later).** Components become Deployments +
  Services + a PVC for Postgres + Secrets for webhook keys; the overdue sweep becomes a
  CronJob. This is the headline DevOps-learning payoff.

M1–M6 = the software. M7–M8 = the infra learning. Don't let M7 creep into M1.

## 9. Spring Boot setup gotchas (from the user's SaaS Playbook — apply at M0)

- **Testcontainers:** add `src/test/resources/docker-java.properties` with
  `api.version=1.44` before running tests (prevents API-version negotiation errors on
  newer Docker). Gate integration tests with
  `@EnabledIfSystemProperty(named="runIntegrationTests", matches="true")` and run via
  `./gradlew test -DrunIntegrationTests=true`.
- **Flyway:** never edit an applied migration — add a new `V`-number. Don't skip numbers.
- **Postgres native enums:** create the TYPE before the table; add new enum values in a
  fresh migration (not transactionally). On the JPA side use `@Enumerated(EnumType.STRING)`
  + `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`.
- **`spring.jpa.hibernate.ddl-auto=validate`** — Flyway owns the schema.
- **Scope exception handlers by annotation, never package:**
  `@RestControllerAdvice(annotations = RestController.class)` — prevents it from
  hijacking any future MVC controllers.
- **Long-running JVM hygiene (matters on the always-on OCI box):**
  `-XX:MaxMetaspaceSize=320m` (default 128m OOMs over days), `-XX:ExitOnOutOfMemoryError`,
  and disable Logback `packagingData`: `<configuration packagingData="false">`.
- **Demo app webhook receiver:** exempt the webhook path from CSRF and `permitAll()` it —
  `.csrf(c -> c.ignoringRequestMatchers("/webhook/**"))` +
  `.requestMatchers("/webhook/**").permitAll()`.
- **Before adding a mapping,** grep existing controllers for the path to avoid
  `Ambiguous mapping` at startup.
- **Bulk inserts** (if ever needed): `JdbcTemplate.batchUpdate()` in chunks, not
  `saveAll()`.

## 10. Open-source & validation notes

The user validates by **shipping free + instrumenting behavior**, NOT by interviewing
people. So:
- Make the repo **public** with a sharp README (positioning + `docker compose up`
  quickstart + add-the-starter snippet). The README *is* the pitch.
- Adoption signal = GitHub stars/forks/issues + who opens integration issues; later,
  Maven Central download counts once the starter is published.
- A **fake-door landing page** for the future hosted tier ("get a hosted API key" button)
  measures willingness-to-pay later — build it only after the M5 loop works.
- Caveat to keep in mind: dev-tool adoption is slow/sparse (high-effort integration), and
  usage validates "will they adopt," not "will they pay." Drive a little distribution
  (dev.to, r/brdev, good docs) or you'll just be measuring your own silence.

## 11. DevOps learning trajectory (the other half of the point)

The whole stack is deliberately multi-service + stateful so it's a good teacher:
- **Phase 1 (M7):** raw Linux box → docker compose, nginx reverse proxy, Let's Encrypt,
  systemd. Learn what a PaaS abstracts.
- **Phase 2 (M8):** single-node **k3s** on the OCI Ampere A1 (4 OCPU / 24 GB) → Deployments,
  Services, PVC (Postgres), Secrets (webhook keys), CronJob (overdue sweep). Kubernetes
  is the skill that most strengthens a senior-Java freelance profile.

**OCI guardrail:** the free tier carries fraud-termination + capacity risk. Zelo on OCI
is a **learning lab + disposable host** — never anything revenue-critical. If the account
dies one morning it should cost only a rebuild. Keep a `docker compose` path that runs
anywhere so you're never locked to OCI.

## 12. Decisions to confirm when the build session starts

1. Gradle Groovy vs Kotlin DSL — match the user's other repos.
2. Spring Data JDBC vs JPA for the mutable aggregates (ledger stays JDBC regardless).
3. Base package name (e.g. `dev.thiago.zelo` or `io.github.<user>.zelo`) + Maven groupId.
4. Confirm `zelo.dev` / `usezelo.com.br` availability before any landing page (note:
   "Grupo Zelo" funeral group and "Zelle" payments exist — fine for an OSS dev lib,
   but check the domain).
5. Working mode: user drives the Java and Claude reviews/architects, with hands-on
   pairing on the OCI/infra side (the actual new learning) — confirm or override.

## 13. Pointers for the build session

- **Read first:** `~/dev/saas-playbook.md` (full gotchas), and the memory files on Java
  DDD layering, CI verification, and validation style.
- **Conventions:** verify CI green after every push; commits should look like solo work
  (no Claude attribution) — this is a portfolio repo; ask before commit/push.
- **Reference impls:** the user's `kdtree4j` (repo conventions/CI), `cobri` (Spring Boot 3
  SaaS patterns), and the four dogfooded starters above.
