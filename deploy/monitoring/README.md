# Monitoring, alerting & incident response

Zelo runs on one box. Without external monitoring a failure is invisible until a
customer reports it, and without off-box logs a compromise can't be scoped. This is
the minimum to know the service is up, know when webhooks are failing, and have the
forensic trail an operador's breach duty (LGPD Art. 48) needs.

## 1. External uptime + alerting

A box-side check can't detect the box being down, so the probe has to run off-box.

> **Cost warning — OCI Health Checks is NOT an Always Free service.** On this tenancy
> (Brazil, Pay-As-You-Go) the SKU `B90323 "Health Checks - Basic"` billed
> **R$1.57 per monitored endpoint per month** (+ tax) and was the *only* charge the
> tenancy ever produced. The `zelo-api-uptime` monitor and its `zelo-api-down` alarm
> were **removed on 2026-09-10** to keep the box at R$0. (Oracle Brazil invoices are
> settled through Oracle Store / Customer Center, not the OCI console.)

**Free option (current recommendation):** an external monitor with a free tier — e.g.
UptimeRobot (5-min HTTPS checks, e-mail alerts) — pointed at the public, unauthenticated
`GET https://api.zelocompliance.com/v1/audit/verify/demo` (always 200, no key, cheap,
already rate-limited at the edge). No OCI resources, no bill.

**Paid option:** [`uptime-setup.sh`](uptime-setup.sh) recreates the OCI Health Check +
Notifications + alarm stack (60-s probes from OCI vantage points). It refuses to run
unless `I_ACCEPT_HEALTH_CHECKS_BILLING=1` is set, so the charge can't be re-enabled by
accident. The `zelo-alerts` Notifications topic and its confirmed e-mail subscription
were kept (free) for reuse by any future alarm on free service metrics.

Tear-down, should the paid stack ever be recreated:

```sh
oci health-checks http-monitor delete --monitor-id <ocid> --force
oci monitoring alarm delete --alarm-id <ocid> --force
```

Cost check at any time (every line should read 0.00):

```sh
oci usage-api usage-summary request-summarized-usages --tenant-id <tenancy-ocid> \
  --time-usage-started <YYYY-MM-01T00:00:00Z> --time-usage-ended <YYYY-MM-DDT00:00:00Z> \
  --granularity MONTHLY --query-type COST --group-by '["service","skuName"]'
```

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
