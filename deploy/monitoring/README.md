# Monitoring, alerting & incident response

Zelo runs on one box. Without external monitoring a failure is invisible until a
customer reports it, and without off-box logs a compromise can't be scoped. This is
the minimum to know the service is up, know when webhooks are failing, and have the
forensic trail an operador's breach duty (LGPD Art. 48) needs.

## 1. External uptime + alerting (OCI Health Checks)

A box-side check can't detect the box being down. OCI **Health Checks** probe from
external vantage points (free) and an OCI **Monitoring alarm** emails a Notifications
topic when the probe fails. The liveness target is the public, unauthenticated
`GET /v1/audit/verify/demo` (always 200, no key, cheap).

```sh
COMPARTMENT_OCID=<your compartment ocid> ALERT_EMAIL=you@example.com \
  ./deploy/monitoring/uptime-setup.sh        # run from a host with the OCI CLI
```

Then **confirm the email subscription** OCI sends. Verify in the console under
*Observability → Health Checks* and *Monitoring → Alarms*. (The alarm query uses the
`oci_healthchecks` namespace; adjust in-console if a future OCI metric name differs.)

## 2. In-app observability (already wired in the control plane)

- **Health:** `GET /actuator/health` → `{"status":"UP"}` (the container healthcheck and
  the uptime probe both key off the app being reachable; the mail indicator is disabled
  so an SMTP outage never marks the plane down).
- **Webhook backlog:** the `OutboxHealthJob` WARNs whenever a webhook event is
  dead-lettered (FAILED) or piling up retries, and publishes the gauge
  **`zelo.outbox.failed`** at `GET /actuator/metrics/zelo.outbox.failed`. The outbox
  library also exposes `outbox.events.published` / `.failed` / `.dead_lettered` counters.
  A non-zero `zelo.outbox.failed` means an integrator endpoint is failing — inspect
  `last_error` in `outbox_event`.
  > Known gap: the outbox library (0.2.x) retries at a fixed interval with **no
  > exponential backoff** (a `next_attempt_at` column is a library-level change). This
  > job is the operator-visibility half; backoff is tracked separately.

## 3. Logs

Container logs are JSON-file rotated on the box (`max-size: 10m`, `max-file: 5` — see
`docker-compose.yml`), so disk can't fill. For breach-scoping they should also leave
the box. Lightweight option, reusing the backup PAR (`/etc/zelo-backup.env`):

```sh
# daily: ship the last 24h of each service's logs to the backup bucket
cd ~/zelo && docker compose logs --since 24h --no-color \
  | gzip > /tmp/zelo-logs-$(date -u +%Y%m%d).log.gz
# upload with the same PAR base used for backups (see deploy/backup/)
```

Wire it as a systemd timer like `zelo-backup.timer`, or run the OCI Logging agent for
a managed pipeline. (Off-box log shipping beyond rotation is a recommended follow-up,
not yet automated here.)

## 4. Edge rate-limiting

See [`../nginx/api-ratelimit.conf`](../nginx/api-ratelimit.conf): a per-IP `limit_req`
on the public `/v1/audit/verify/demo` (keyed on the trusted `$binary_remote_addr`) plus
overwriting `X-Forwarded-For` with the real client IP so the in-app per-IP limiter can't
be spoofed. Authed `/v1/*` is key-gated and deliberately not broadly throttled.
