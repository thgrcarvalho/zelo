# Integrating Zelo into your app

This guide walks a real Spring Boot application through becoming a Zelo integrator:
declaring processing purposes, recording consent, and routing account deletion
through Zelo's tamper-evident audit trail. The running example is **Vitalio**
(`br.com.usevitalio`, Java 21 / Spring Boot 3.4.5, Gradle, Spring Security + OAuth2,
`application.properties`), but nothing here is Vitalio-specific.

> **Reminder of the contract.** Zelo is a *control plane*, not a data store. It only
> ever sees your opaque `external_id`. Your users' PII (names, emails, CPF, dates of
> birth, health data) never leaves your database. Zelo records *that* consent was
> given and *that* deletion happened — the tamper-evident proof — and orchestrates
> the erasure via a signed webhook back to you.

The bundled [`zelo-demo`](zelo-demo) module is a runnable, tested reference of
everything below — read it alongside this guide.

---

## 1. Resolve the starter

The starter is published to your local Maven repository. In the **Zelo** repo:

```bash
./gradlew :zelo-spring-boot-starter:publishToMavenLocal
```

Then in **your app** (`build.gradle`), make sure `mavenLocal()` is a repository and add
the dependency:

```groovy
repositories {
    mavenLocal()      // resolves zelo-spring-boot-starter + its pix-webhook-validator
    mavenCentral()
}

dependencies {
    implementation 'io.github.thgrcarvalho:zelo-spring-boot-starter:0.1.0'
}
```

The starter is built against Spring Web as `compileOnly`, so it adds no Spring
version of its own — it uses the web stack your app already has. It pulls in
`pix-webhook-validator` (runtime) for HMAC signature checking.

---

## 2. Configure

The starter binds `zelo.*`. In Vitalio's `application.properties`:

```properties
zelo.api-url=${ZELO_API_URL:http://localhost:8080}
zelo.api-key=${ZELO_API_KEY}
zelo.webhook-secret=${ZELO_WEBHOOK_SECRET}
zelo.webhook-path=/zelo/webhooks
# optional: clock-skew tolerance for inbound webhooks, seconds (default 300)
zelo.webhook-tolerance-seconds=300
```

You need a running Zelo control plane and an API key provisioned for your app, whose
webhook target points back at `https://<your-app>/zelo/webhooks`. For local
development, run Zelo from this repo (`docker compose up`) with the bootstrap env
pointing at your app's webhook URL; in production that provisioning lives wherever
you deploy Zelo.

The client carries its **own `snake_case` JSON mapper**, so it works regardless of
your app's Jackson naming strategy — Vitalio's default `camelCase` is fine, nothing
to change.

---

## 3. Choose an `external_id`

Zelo keys everything off an opaque, stable `external_id` (a string). Vitalio's users
are identified by an auto-increment `Long id`, which works but is enumerable. Prefer
a **stable, opaque, non-PII** identifier:

- **Recommended:** add an `external_id UUID` column to `users` (a Flyway migration,
  backfilled `gen_random_uuid()`, `NOT NULL UNIQUE`). It is non-enumerable and never
  reused even if a numeric id ever is.
- **Quick start:** `"vitalio-user-" + user.getId()` — fine to begin with; migrate to
  a UUID before you have meaningful volume.

Whatever you pick, it must (a) be stable for the life of the user and (b) let your
webhook handler resolve back to the row to erase. Keep a single helper:

```java
String externalId = user.getExternalId().toString();   // or "vitalio-user-" + user.getId()
```

Never put PII (email, CPF, name) in the `external_id`.

---

## 4. Declare your purposes at startup

Consent is always recorded *against* a declared purpose, so declare them once on
boot. `definePurpose` is idempotent (declaring an existing key returns it), so this
is safe on every restart. Map your existing toggles to purposes:

| Vitalio concept (today)              | Zelo purpose key        | Legal basis (LGPD Art. 7)        |
|--------------------------------------|-------------------------|----------------------------------|
| using the product (ToS)              | `terms-of-service`      | `CONTRACT` (V)                   |
| `reminder_emails_enabled`            | `reminder-emails`       | `CONSENT` (I)                    |
| `ai_drafts_enabled`                  | `ai-drafts`             | `CONSENT` (I)                    |
| beneficiary CPF / DOB processing     | `health-data-processing`| `HEALTH_PROTECTION` (VIII / 11)  |

```java
@Component
@ConditionalOnProperty(prefix = "zelo", name = "api-key")   // only when Zelo is configured
public class ZeloPurposes implements ApplicationRunner {

    public static final String TERMS = "terms-of-service";
    public static final String REMINDER_EMAILS = "reminder-emails";
    public static final String AI_DRAFTS = "ai-drafts";
    public static final String HEALTH_DATA = "health-data-processing";

    private final ZeloClient zelo;

    ZeloPurposes(ZeloClient zelo) { this.zelo = zelo; }

    @Override public void run(ApplicationArguments args) {
        zelo.definePurpose(TERMS, "Provide the service under our terms", ZeloLegalBasis.CONTRACT);
        zelo.definePurpose(REMINDER_EMAILS, "Send reminder / win-back emails", ZeloLegalBasis.CONSENT);
        zelo.definePurpose(AI_DRAFTS, "Generate AI message drafts", ZeloLegalBasis.CONSENT);
        zelo.definePurpose(HEALTH_DATA, "Process beneficiary health data", ZeloLegalBasis.HEALTH_PROTECTION);
    }
}
```

(See `zelo-demo`'s `PurposeRegistrar` for a version that also waits for the control
plane to come up.)

---

## 5. Record consent at the real decision points

`ZeloClient` gives you `grantConsent` / `withdrawConsent` (and `recordConsent` with
optional PII-free metadata). Wire them to the places consent actually changes:

```java
// On signup:
zelo.registerSubject(externalId);                                  // optional; consent auto-registers
zelo.grantConsent(externalId, ZeloPurposes.TERMS, "signup-form");
zelo.grantConsent(externalId, ZeloPurposes.HEALTH_DATA, "signup-form");

// Vitalio already has POST /perfil/preferencias-emails — mirror the toggle into Zelo:
if (enabled) zelo.grantConsent(externalId, ZeloPurposes.REMINDER_EMAILS, "account-settings");
else         zelo.withdrawConsent(externalId, ZeloPurposes.REMINDER_EMAILS, "account-settings");

// Same for POST /perfil/preferencias-ia -> ZeloPurposes.AI_DRAFTS
```

You can keep your local boolean column as a fast cache and treat Zelo as the
append-only, tamper-evident *record* of the decision. To **gate** a feature on
current consent:

```java
if (zelo.isGranted(externalId, ZeloPurposes.AI_DRAFTS)) {
    // ...generate the AI draft
}
```

`isGranted` returns `false` for an unknown subject, so it never throws for a
never-seen user.

---

## 6. Route account deletion through Zelo

Today Vitalio's `POST /perfil/excluir-conta` hard-deletes immediately (cascade). To
get the tamper-evident proof, let Zelo orchestrate it instead:

**Step 1 — the delete endpoint opens a Zelo request** (instead of deleting directly):

```java
@PostMapping("/perfil/excluir-conta")
public String requestDeletion(@AuthenticationPrincipal Account me) {
    zelo.requestDeletion(externalIdOf(me));   // Zelo will webhook us back to erase
    return "redirect:/perfil?delecao-solicitada";
}
```

**Step 2 — a `@ZeloWebhook` handler does the actual erasure** and returns proof. The
starter verifies the HMAC signature + freshness, calls you, and auto-fulfills the
request with your return value:

```java
@Component
public class AccountErasure {

    private final UserRepository users;   // your existing repository

    AccountErasure(UserRepository users) { this.users = users; }

    @ZeloWebhook("dsr.delete.requested")
    @Transactional
    public Map<String, Object> erase(ZeloDeletionRequest request) {
        // Resolve external_id -> your row, then run your existing cascade delete.
        long deleted = users.deleteByExternalId(request.externalId());   // CASCADE wipes leads, contratos, ...
        return Map.of("deletedRows", deleted);   // becomes the audited fulfillment proof
    }
}
```

Throwing from the handler tells Zelo the erasure failed; it retries with backoff, and
if the deadline passes the request is flagged `OVERDUE` (an audited SLA miss).

Because Vitalio's erasure is near-instant, the loop completes in ~1s. `requestDeletion`
returns the opened `ZeloRequest` (status `RECEIVED`, with its deadline); if you want the
endpoint to confirm completion before responding, poll on its id:

```java
ZeloRequest req = zelo.requestDeletion(externalId);   // status RECEIVED
// ...shortly after, once the webhook round-trip has run:
boolean done = zelo.getRequest(req.id()).isFulfilled();
```

> Keeping immediate deletion? You can still record it: call `requestDeletion` then,
> after your own delete, `fulfill(requestId, proof)`. But the webhook model above is
> cleaner and is what the demo proves end-to-end.

---

## 7. Let the webhook through your security filter

**This is the one easy-to-miss step in a secured app.** Vitalio uses Spring Security
(session + OAuth2). The inbound webhook at `/zelo/webhooks` is authenticated by its
**HMAC signature**, not by a session — so your `SecurityFilterChain` must permit it
(and exempt it from CSRF), otherwise Spring Security returns 302/403 and the webhook
never reaches the handler:

```java
http
    .authorizeHttpRequests(a -> a
        .requestMatchers("/zelo/webhooks").permitAll()
        .anyRequest().authenticated())
    .csrf(c -> c.ignoringRequestMatchers("/zelo/webhooks"));
```

The signature check (wrong/missing signature → 401, stale timestamp → 400) is the
real authentication for this endpoint.

---

## 8. Prove the proof (optional dashboard)

Surface the tamper-evident trail to admins or to the user:

```java
ZeloAuditVerification v = zelo.verifyAudit();           // {ok, entriesChecked, firstBrokenEntryId, detail}
List<ZeloAuditEntry> recent = zelo.exportAudit(200);    // consent.* + dsr.delete.* events
ZeloConsentReport report = zelo.getConsent(externalId); // current state + full history per purpose
```

`verifyAudit().ok()` recomputes the whole hash chain — a green check you can show as a
compliance-health indicator.

---

## `ZeloClient` reference

| Method | Calls | Notes |
|---|---|---|
| `registerSubject(externalId)` | `POST /v1/subjects` | idempotent upsert; optional (consent/requests auto-register) |
| `definePurpose(key, desc, legalBasis)` | `POST /v1/purposes` | **idempotent** — returns the existing purpose on conflict |
| `listPurposes()` | `GET /v1/purposes` | |
| `recordConsent(externalId, purposeKey, action, source, metadata)` | `POST /v1/consents` | append-only; returns current state + history |
| `grantConsent` / `withdrawConsent(externalId, purposeKey, source)` | `POST /v1/consents` | shorthands |
| `getConsent(externalId[, purposeKey])` | `GET /v1/consents` | throws `NotFound` for an unknown subject |
| `isGranted(externalId, purposeKey)` | `GET /v1/consents` | `false` for unknown subject (never throws) |
| `requestDeletion(externalId)` | `POST /v1/requests` | returns the new `ZeloRequest` (id, status, deadline); fires `dsr.delete.requested` |
| `getRequest(requestId)` | `GET /v1/requests/{id}` | status, deadline countdown, proof |
| `fulfill(requestId, proof)` | `POST /v1/requests/{id}/fulfill` | idempotent (409 treated as success) |
| `verifyAudit()` | `GET /v1/audit/verify` | recompute + verify the hash chain |
| `exportAudit(limit)` | `GET /v1/audit` | recent entries (server caps at 1000) |

Server errors surface as `RestClientResponseException` (e.g. `HttpClientErrorException.NotFound`),
except where a method documents that it absorbs a specific status.
