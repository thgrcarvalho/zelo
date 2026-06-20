#!/usr/bin/env bash
#
# Nightly Postgres backup for a self-hosted Zelo control plane.
#
# Dumps the `zelo` database from the compose Postgres container, gzips it to a
# local backup dir, optionally uploads it off-box to OCI Object Storage via a
# pre-authenticated request (PAR) URL — so NO OCI credentials live on the box —
# and prunes local copies past the retention window. Idempotent; safe to re-run.
#
# The audit chain, consent ledger, accounts and api_keys all live in one Docker
# volume on one host; without this, a volume loss is unrecoverable destruction of
# every tenant's legal evidence. Pair it with an off-box copy (PAR upload below or
# an OCI Block Volume backup policy) — local copies alone die with the host.
#
# Install: see deploy/backup/README.md (systemd timer) and run a restore drill.
# Config:  override defaults in /etc/zelo-backup.env (sourced if present).
set -euo pipefail

: "${ZELO_COMPOSE_DIR:=/home/ubuntu/zelo}"   # dir holding docker-compose.yml
: "${ZELO_PG_SERVICE:=postgres}"             # compose service name
: "${ZELO_DB_NAME:=zelo}"
: "${ZELO_DB_USER:=zelo}"
: "${BACKUP_DIR:=/var/backups/zelo}"
: "${RETENTION_DAYS:=14}"
: "${BACKUP_PAR_BASE:=}"                      # OCI PAR base URL ending in /o/ ; blank = local only
[ -f /etc/zelo-backup.env ] && . /etc/zelo-backup.env

ts="$(date -u +%Y%m%dT%H%M%SZ)"
file="zelo-${ZELO_DB_NAME}-${ts}.sql.gz"
mkdir -p "$BACKUP_DIR"
dest="$BACKUP_DIR/$file"

dc() { docker compose -f "$ZELO_COMPOSE_DIR/docker-compose.yml" exec -T "$ZELO_PG_SERVICE" "$@"; }

echo "[zelo-backup] dumping ${ZELO_DB_NAME} -> ${dest}"
# --clean --if-exists makes the dump self-contained for a drop-in restore. Guard the
# pipe explicitly: with set -e + pipefail a failed pg_dump would abort the script and
# leave a partial .sql.gz behind (which the restore drill could then pick as "latest").
if ! dc pg_dump -U "$ZELO_DB_USER" --clean --if-exists "$ZELO_DB_NAME" | gzip -9 > "$dest"; then
  echo "[zelo-backup] ERROR: pg_dump/gzip failed — removing partial ${dest}" >&2
  rm -f "$dest"
  exit 1
fi

# Guard against a truncated/empty-but-zero-exit dump.
size=$(stat -c%s "$dest")
if [ "$size" -lt 1024 ]; then
  echo "[zelo-backup] ERROR: dump is only ${size} bytes — refusing to keep it" >&2
  rm -f "$dest"
  exit 1
fi
echo "[zelo-backup] wrote ${size} bytes"

# Off-box copy via a write-enabled PAR (secretless on the box). The object lands
# at ${BACKUP_PAR_BASE}${file}. Set a lifecycle/retention rule on the bucket for
# remote pruning (see README); this script prunes only the local copies.
if [ -n "$BACKUP_PAR_BASE" ]; then
  echo "[zelo-backup] uploading off-box -> ${file}"
  curl -fsS -T "$dest" "${BACKUP_PAR_BASE}${file}" >/dev/null \
    && echo "[zelo-backup] off-box upload ok" \
    || { echo "[zelo-backup] WARN: off-box upload failed (local copy kept)" >&2; }
fi

find "$BACKUP_DIR" -name 'zelo-*.sql.gz' -mtime "+${RETENTION_DAYS}" -delete
echo "[zelo-backup] done; local copies retained: $(ls -1 "$BACKUP_DIR"/zelo-*.sql.gz 2>/dev/null | wc -l)"
