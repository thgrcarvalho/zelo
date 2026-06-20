# Deploy assets — self-service dashboard (`/app`) + account API proxy

These are the **box-side** artifacts for the public web surface (landing, dashboard,
docs) and the account API proxy. They sit in the repo (versioned, reviewable) but are
applied to the OCI host by hand. Nothing here is built by Gradle or CI; the only
CI-gated change is the backend + `docker-compose.yml` env wiring.

`deploy/site/` is a 1:1 mirror of the box web root `/var/www/zelocompliance/`, so a
deploy is a single `rsync` (step 4). All three pages share one **visual identity**
(below); fonts are self-hosted — a zero-PII product makes no third-party font request.

```
deploy/
├── site/                       # full static web root  ->  /var/www/zelocompliance/
│   ├── index.html              #   landing                (/)
│   ├── app/index.html          #   self-service dashboard (/app/)
│   ├── docs/index.html         #   integration guide      (/docs/)
│   ├── assets/fonts/*.woff2     #   self-hosted brand fonts (OFL)
│   ├── assets/og.png           #   social share image
│   └── favicon.svg · favicon.ico · favicon-32/16.png · apple-touch-icon.png
└── nginx/account-proxy.conf    # the /account/** -> :8080 location blocks (landing vhost)
```

### Visual identity

Dark "warm violet-black" theme. Signature **violet `#8B6CFF`** (brand/primary),
**cyan `#4FD8E6`** (verified/proof accents), green=success, amber=warn, rose=danger.
The logo is an append-only **"Z"** mark whose violet diagonal links two committed
record bars (the consent ledger / hash chain as a letterform). Type: **Space Grotesk**
(display) · **IBM Plex Sans** (UI/body) · **JetBrains Mono** (code, hashes, keys).
Design tokens are inline `:root` vars per page; all text clears WCAG-AA contrast.

Onboarding is **instant + email-verified**: a developer signs up, clicks the link in
the verification email, and is immediately ACTIVE — there is no operator and no
approval queue. The one new external dependency is a transactional email provider
(Resend by default).

## Prerequisite: the backend must be live first

The dashboard calls `/account/**`, which only exists once the **self-service accounts
backend** is merged and deployed. Deploy in this order:

1. Merge + deploy the backend (the `V8` + `V9` migrations run on rebuild; `/account/**`
   comes online).
2. Then apply the steps below.

Applying the `docker-compose.yml` env change before the backend is harmless — the
running image simply ignores the new variables until the code is present.

## 1. Set up the email provider (Resend)

Verification + password-reset email is sent over SMTP. Resend's free tier is ample
for onboarding; any SMTP provider (e.g. Brevo) works by overriding `ZELO_SMTP_*`.

1. Create a Resend account and **verify a sending domain**. Prefer a dedicated
   subdomain (`send.zelocompliance.com`) so mail-stream reputation is isolated from
   the apex and existing records are untouched.
2. Resend shows ~3–4 DNS records (an SPF `TXT`, the DKIM keys, and a `DMARC` `TXT`,
   e.g. `v=DMARC1; p=none; rua=...`). Add them on `zelocompliance.com` via the scoped
   Cloudflare token on the box (`~/.config/zelo-cf-token`) and the CF REST API.
3. Generate a Resend **API key** — this becomes `ZELO_SMTP_PASSWORD` below.

## 2. Secrets — add to `~/zelo/.env`

`.env` is untracked and survives `git reset --hard` (so does
`docker-compose.override.yml`); just never run `git clean -fdx`, which would delete
both. `.env` does **not** run command substitution, so generate the session secret in
the shell and append the literal value — don't paste an `$(...)` placeholder:

```sh
printf 'ZELO_AUTH_SECRET=%s\n' "$(openssl rand -base64 48)"      >> ~/zelo/.env
printf 'ZELO_MAIL_ENABLED=%s\n' "true"                          >> ~/zelo/.env
printf 'ZELO_MAIL_FROM=%s\n' "no-reply@send.zelocompliance.com" >> ~/zelo/.env
printf 'ZELO_MAIL_BASE_URL=%s\n' "https://zelocompliance.com"   >> ~/zelo/.env
printf 'ZELO_SMTP_PASSWORD=%s\n' "<resend-api-key>"             >> ~/zelo/.env
```

Fail-closed knobs:
- A blank `ZELO_AUTH_SECRET` leaves `/account` off (signup/login 503).
- `ZELO_MAIL_ENABLED` not `true` (or a blank `ZELO_MAIL_FROM`/`ZELO_MAIL_BASE_URL`)
  with verification required (the default) makes **signup return 503** rather than
  create an account nobody can verify. So this step is what turns onboarding on.
- `ZELO_MAIL_BASE_URL` must be the canonical `https` host with no path — it builds the
  verify/reset links (never taken from the request `Host`).

`ZELO_SMTP_HOST`/`PORT`/`USERNAME` default to Resend; override only to swap provider.

## 3. Rebuild the control plane (picks up the new env + migrations)

```sh
cd ~/zelo && git fetch --depth 1 origin main && git reset --hard FETCH_HEAD
docker compose up -d --build
```

The bundled `zelo-demo` integrator is **opt-in** (compose `demo` profile), so a plain
`docker compose up -d --build` runs the control plane only — prod never starts the
demo PII app. The control plane also seeds **no** API key unless `ZELO_DEMO_API_KEY`
is set: leave it **unset** in the prod `.env` (real tenants are minted via the
dashboard signup or the `/admin` API) so no `demo` tenant is created.

**Migrating an existing box** that previously set these: remove the legacy
`ZELO_DEMO_API_KEY` and `ZELO_DEMO_WEBHOOK_SECRET` from `~/zelo/.env`, then
`docker compose up -d --build`. A `zelo-demo` container left running from the old
config is **not** removed by `--remove-orphans` (a profile-gated service isn't an
orphan) — stop it explicitly: `docker rm -f zelo-zelo-demo-1` (or
`docker compose --profile demo rm -sf zelo-demo`). Optionally revoke the pre-existing
`demo` key in Postgres: `docker compose exec -T postgres psql -U zelo -d zelo -c
"UPDATE api_keys SET revoked_at=now() WHERE name='demo' AND revoked_at IS NULL;"`.
Vitalio's separately-minted key is untouched.

## 4. Publish the static site (landing + dashboard + docs + assets)

`deploy/site/` mirrors the web root, so one `rsync` publishes everything. No `--delete`,
so box-only files (`*.bak`, the waitlist store) stay untouched:

```sh
# On the box (both paths local — plain sudo, NOT --rsync-path, which is remote-only):
sudo rsync -a ~/zelo/deploy/site/ /var/www/zelocompliance/
sudo chown -R www-data:www-data /var/www/zelocompliance
```

(From a workstation, over ssh: `rsync -az --rsync-path="sudo rsync" -e "ssh -i <key>" \
deploy/site/ <user>@<host>:/var/www/zelocompliance/` — `--rsync-path` makes the
*remote* rsync run under sudo, so it only applies to the ssh form.)

`/`, `/app/`, and `/docs/` all resolve via the landing vhost's existing
`try_files $uri $uri/` — no nginx change is needed *for the static pages*. The
verify/reset email links are
`/app/#verify=<token>` / `/app/#reset=<token>`; the token rides the URL **fragment**,
which the browser never sends to the server (so it's never in nginx logs or `Referer`).

The landing's **live proof widget** fetches `GET /v1/audit/verify/demo` on the API host
— a **public, unauthenticated** endpoint that recomputes a synthetic *showcase* audit
chain from genesis and returns `{"ok":true,"entries_checked":N}`. It is the single
anonymous exception under `/v1/*` (every other path stays API-key-guarded), exposes
only the integrity verdict + count (never any payload or tenant data), and is
**CORS-allowed for `https://zelocompliance.com` in the app itself** — so no nginx change
is *required* for it to work. Toggle with `ZELO_SHOWCASE_ENABLED` (default `true`).

The app applies a per-IP `@RateLimit` (60/min) as defense-in-depth, but it keys on
`X-Forwarded-For`, which a client can spoof. For real DoS protection add a coarse
`limit_req` on the **api vhost's** `/v1/audit/verify/demo` location keyed on the trusted
`$binary_remote_addr` (the same shape as the `/account/*` auth limits below), and make
that vhost derive the client IP from a trusted hop (`set_real_ip_from` +
`real_ip_recursive`, or `proxy_set_header X-Forwarded-For $remote_addr`) rather than
trusting the inbound header. The recompute is cheap (~8 hashes over the demo chain), so
this is a low-severity hardening step, not a launch blocker.

## 5. Wire the account API proxy — from `deploy/nginx/account-proxy.conf`

Add the one `limit_req_zone` line to the **http{} context** (e.g. a file under
`/etc/nginx/conf.d/`), and paste the `location` blocks into the **TLS server block**
of the landing vhost (`server_name zelocompliance.com www.zelocompliance.com;`), then:

```sh
sudo nginx -t && sudo systemctl reload nginx
```

Do **not** add `/account/` to the `api.zelocompliance.com` vhost — it must stay on the
dashboard origin only (session-cookie auth, same origin as `/app`).

**Canonical host:** the session cookie is host-only (no `Domain`), so `/app` and
`/account` must be used on a single host. The landing vhost serves both
`zelocompliance.com` and `www.zelocompliance.com`; pick one canonical host (e.g.
redirect `www` → apex) so a login on one host isn't invisible on the other. Don't add
a `Domain` attribute to widen the cookie. As defense-in-depth, a default/catch-all
nginx `server` that rejects unmatched `Host` headers is recommended (the app already
builds email links from the configured base URL, never the request `Host`).

## 6. Back up Postgres (do not skip)

The hash-chained audit trail, the consent ledger, and every account live in one
Postgres volume on one box. Install the nightly dump + off-box upload + a **tested
restore** before onboarding anyone — see **[`backup/README.md`](backup/README.md)**.
One `docker compose down -v` or a lost host otherwise destroys every tenant's legal
evidence, which is the one thing this product exists to make trustworthy.

```sh
sudo install -m 0755 deploy/backup/zelo-pg-backup.sh  /usr/local/bin/zelo-pg-backup.sh
sudo install -m 0755 deploy/backup/zelo-pg-restore.sh /usr/local/bin/zelo-pg-restore.sh
sudo install -m 0644 deploy/backup/zelo-backup.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now zelo-backup.timer
sudo systemctl start zelo-backup.service                 # take one now
/usr/local/bin/zelo-pg-restore.sh "$(ls -1t /var/backups/zelo/*.sql.gz | head -1)"  # drill
```

## Verify

```sh
# Proxy + filter reachable (this 401 alone does NOT prove the secret is set —
# a blank ZELO_AUTH_SECRET would make signup/login 503, but /me still 401s):
curl -si https://zelocompliance.com/account/me | head -1        # HTTP/2 401
# Dashboard loads:
curl -sI https://zelocompliance.com/app/ | head -1              # HTTP/2 200
# Landing proof widget — PUBLIC, no auth, CORS-allowed for the site; verifies the
# synthetic showcase chain (no tenant data, verdict + count only):
curl -s https://api.zelocompliance.com/v1/audit/verify/demo     # {"ok":true,"entries_checked":8,...}
# FUNCTIONAL check — signup must return 202 (not 503). A 503 means mail/secret is
# unconfigured; a 400 is a validation error.
curl -si -X POST https://zelocompliance.com/account/signup \
  -H 'content-type: application/json' \
  -d '{"email":"you@example.com","password":"a-strong-pass","org_name":"You"}' \
  | head -1                                                      # HTTP/2 202
```

Then a real end-to-end check: sign up with a real Gmail/Outlook address, confirm the
verification email **lands in the inbox (not spam)** — GreenMail tests don't exercise
real DKIM/SPF/DMARC or the 465 TLS handshake — click the link, and self-issue a key.
Retire any handoff-minted key once the self-issued one is live.
