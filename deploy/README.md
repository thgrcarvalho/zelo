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

**1. Secrets** — add to `~/zelo/.env` (untracked; survives `git reset`):

```sh
ZELO_AUTH_SECRET=<openssl rand -base64 48>
ZELO_OPERATOR_EMAIL=<your operator email>
ZELO_OPERATOR_PASSWORD=<a strong password>
```

A blank `ZELO_AUTH_SECRET` leaves `/account` fail-closed (no logins), so this step is
what turns the dashboard on. The operator account is seeded once, on startup.

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

**4. Wire the account API proxy** — paste the block from
`deploy/nginx/account-proxy.conf` into the **TLS server block** of the landing vhost
(`server_name zelocompliance.com www.zelocompliance.com;`), then:

```sh
sudo nginx -t && sudo systemctl reload nginx
```

Do **not** add `/account/` to the `api.zelocompliance.com` vhost — it must stay on the
dashboard origin only (session-cookie auth, same origin as `/app`).

## Verify

```sh
# Unauthenticated -> 401 JSON (proxy + filter both working):
curl -si https://zelocompliance.com/account/me | head -1        # HTTP/2 401
# Dashboard loads:
curl -sI https://zelocompliance.com/app/ | head -1              # HTTP/2 200
# Operator can sign in (seeded from .env), then work the queue in the UI.
```

Then onboard for real: sign up at `/app`, approve from the operator account, self-issue
a key, and retire any handoff-minted key.
