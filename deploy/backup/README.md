# Postgres backups — the audit chain is the product, so back it up

Zelo's value is an **unforgeable, multi-year proof trail**. It lives, along with the
append-only consent ledger, every account, and every API key, in a single Postgres
volume on a single host. No backup = one `docker compose down -v`, one corrupted
volume, or one dead host away from destroying every tenant's legal evidence. This is
an **operator responsibility for every deployment** — hosted and self-hosted alike.

Three layers, cheapest to strongest:

1. **Nightly logical dump** (`zelo-pg-backup.sh`) — `pg_dump | gzip` to `/var/backups/zelo`.
2. **Off-box copy** — upload each dump to object storage so it survives host loss.
3. **A tested restore** (`zelo-pg-restore.sh`) — a dump you've never restored is a hope.

> Block-volume snapshots are a good *fourth* layer (on OCI: attach a Block Volume
> backup **policy** to the boot/data volume). They're crash-consistent, not
> application-consistent, so they complement — never replace — the logical dump.

## Install (systemd timer)

```sh
sudo install -m 0755 deploy/backup/zelo-pg-backup.sh  /usr/local/bin/zelo-pg-backup.sh
sudo install -m 0755 deploy/backup/zelo-pg-restore.sh /usr/local/bin/zelo-pg-restore.sh
sudo install -m 0644 deploy/backup/zelo-backup.service /etc/systemd/system/zelo-backup.service
sudo install -m 0644 deploy/backup/zelo-backup.timer   /etc/systemd/system/zelo-backup.timer

# Optional config overrides (compose dir, retention, off-box PAR). 0600 — may hold a PAR URL.
sudo tee /etc/zelo-backup.env >/dev/null <<'ENV'
ZELO_COMPOSE_DIR=/home/ubuntu/zelo
RETENTION_DAYS=14
# BACKUP_PAR_BASE=https://objectstorage.<region>.oraclecloud.com/p/<token>/n/<ns>/b/zelo-backups/o/
ENV
sudo chmod 0600 /etc/zelo-backup.env

sudo systemctl daemon-reload
sudo systemctl enable --now zelo-backup.timer
systemctl list-timers zelo-backup.timer        # confirm next run
sudo systemctl start zelo-backup.service        # run one now
journalctl -u zelo-backup.service --no-pager | tail
```

## Off-box upload (OCI Object Storage, Always-Free, no creds on the box)

A **pre-authenticated request (PAR)** is a capability URL: the box can write objects
to one bucket with it and nothing else — no OCI API key on the box. Create the bucket
and PAR from a machine that already has the OCI CLI configured (e.g. the workstation):

```sh
NS=$(oci os ns get --query data --raw-output)
oci os bucket create --name zelo-backups --compartment-id "$OCI_COMPARTMENT_OCID"

# Write-only PAR (AnyObjectWrite), 1 year. Prints accessUri exactly once — save it.
oci os preauth-request create --bucket-name zelo-backups \
  --name zelo-box-backup --access-type AnyObjectWrite \
  --time-expires "$(date -u -d '+1 year' +%Y-%m-%dT%H:%M:%SZ)" \
  --query 'data."access-uri"' --raw-output
# -> /p/<token>/n/<ns>/b/zelo-backups/o/   (prefix with the region objectstorage host)
```

Put the full URL (host + access-uri, ending in `/o/`) in `/etc/zelo-backup.env` as
`BACKUP_PAR_BASE`. **Remote retention:** add a lifecycle rule on the bucket to delete
objects after N days (`oci os object-lifecycle-policy put`) — the script prunes only
local copies. PARs expire — calendar a renewal before the expiry date.

## Restore drill (do this at least once, then periodically)

Restores into a throwaway DB (`zelo_restore_check`), never the live `zelo` DB:

```sh
LATEST=$(ls -1t /var/backups/zelo/zelo-*.sql.gz | head -1)
/usr/local/bin/zelo-pg-restore.sh "$LATEST"
# -> prints audit_log / consent_events / api_keys / accounts row counts; audit_log must be > 0
```

Real recovery after a disaster: stop the app, then `zelo-pg-restore.sh <dump> zelo`.
