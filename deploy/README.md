# Deploy assets — self-service dashboard (`/app`) + account API proxy

These are the **box-side** artifacts for the self-service account surface. They sit
in the repo (versioned, reviewable) but are applied to the OCI host by hand, the same
way the landing page and `/docs/` are. Nothing here is built by Gradle or CI; the only
CI-gated change in this PR is the `docker-compose.yml` env wiring.

```
deploy/
├── app/index.html              # the dashboard, served at https://zelocompliance.com/app/
└── nginx/account-proxy.conf    # the /account/** -> :8080 location block (landing vhost)
```

## Prerequisite: the backend must be live first

The dashboard calls `/account/**`, which only exists once the **self-service accounts
backend** (PR A) is merged and deployed to the box. Deploy in this order:

1. Merge + deploy PR A (the `V8` migration runs on rebuild; `/account/**` comes online).
2. Then apply the steps below.

Applying the `docker-compose.yml` env change before PR A is harmless — the running
image simply ignores the new variables until PR A's code is present.

## Deploy steps (on the box)

Assumes the repo is checked out at `~/zelo` and the landing vhost is
`/etc/nginx/sites-available/zelo-landing` (serving `/var/www/zelocompliance`).

**1. Secrets** — add to `~/zelo/.env`. `.env` is untracked and survives
`git reset --hard` (so does `docker-compose.override.yml`); just never run
`git clean -fdx`, which would delete both.

`.env` does **not** run command substitution, so generate the secret in the shell
and append the literal value — don't paste an `$(...)` placeholder:

```sh
printf 'ZELO_AUTH_SECRET=%s\n' "$(openssl rand -base64 48)" >> ~/zelo/.env
printf 'ZELO_OPERATOR_EMAIL=%s\n' "you@example.com"          >> ~/zelo/.env
printf 'ZELO_OPERATOR_PASSWORD=%s\n' "<a strong password, min 8 chars>" >> ~/zelo/.env
```

A blank `ZELO_AUTH_SECRET` leaves `/account` fail-closed (signup/login return 503),
so this step is what turns the dashboard on. The operator account is seeded once, on
startup; an operator password shorter than 8 characters is refused (no operator is
seeded), matching the floor enforced on self-service signup.

**2. Rebuild the control plane** (picks up the new env + PR A's code/migration):

```sh
cd ~/zelo && git fetch --depth 1 origin main && git reset --hard FETCH_HEAD
docker compose up -d --build
```

**3. Publish the dashboard** to the landing docroot:

```sh
sudo mkdir -p /var/www/zelocompliance/app
sudo cp ~/zelo/deploy/app/index.html /var/www/zelocompliance/app/index.html
sudo chown -R www-data:www-data /var/www/zelocompliance/app
sudo chmod 644 /var/www/zelocompliance/app/index.html
```

`/app/` resolves via the landing vhost's existing `try_files $uri $uri/` — no nginx
change is needed *for the static page*.

**4. Wire the account API proxy** — from `deploy/nginx/account-proxy.conf`:
add the one `limit_req_zone` line to the **http{} context** (e.g. a file under
`/etc/nginx/conf.d/`), and paste the three `location` blocks into the **TLS server
block** of the landing vhost (`server_name zelocompliance.com www.zelocompliance.com;`),
then:

```sh
sudo nginx -t && sudo systemctl reload nginx
```

Do **not** add `/account/` to the `api.zelocompliance.com` vhost — it must stay on the
dashboard origin only (session-cookie auth, same origin as `/app`).

**Canonical host:** the session cookie is host-only (no `Domain`), so `/app` and
`/account` must be used on a single host. The landing vhost serves both
`zelocompliance.com` and `www.zelocompliance.com`; pick one canonical host (e.g.
redirect `www` → apex) so a login on one host isn't invisible on the other. Don't add
a `Domain` attribute to widen the cookie.

## Verify

```sh
# Proxy + filter reachable (this 401 alone does NOT prove the secret is set —
# a blank ZELO_AUTH_SECRET would make signup/login 503, but /me still 401s):
curl -si https://zelocompliance.com/account/me | head -1        # HTTP/2 401
# Dashboard loads:
curl -sI https://zelocompliance.com/app/ | head -1              # HTTP/2 200
# FUNCTIONAL check — operator login must return 200 + a Set-Cookie. A 503 here
# means ZELO_AUTH_SECRET is blank; a 401 means wrong creds.
curl -si -X POST https://zelocompliance.com/account/login \
  -H 'content-type: application/json' \
  -d '{"email":"you@example.com","password":"<operator password>"}' \
  | grep -Ei '^HTTP/|^set-cookie'                                # HTTP/2 200 + set-cookie: zelo_session=...
```

Then onboard for real: sign up at `/app`, approve from the operator account, self-issue
a key, and retire any handoff-minted key.
