#!/usr/bin/env bash
#
# Restore a Zelo pg_dump (.sql.gz) to verify a backup is actually usable.
#
# A backup you've never restored is a hope, not a backup. By default this loads
# the dump into a THROWAWAY database (zelo_restore_check) so it never touches the
# live `zelo` DB, then prints row counts of the critical tables so you can eyeball
# that the data is really there. Use it as the periodic restore drill.
#
#   ./zelo-pg-restore.sh /var/backups/zelo/zelo-zelo-20260620T030000Z.sql.gz
#
# To restore for real into the live DB after a disaster, pass `zelo` as the target
# (the live app should be stopped first):  ./zelo-pg-restore.sh <dump> zelo
set -euo pipefail

: "${ZELO_COMPOSE_DIR:=/home/ubuntu/zelo}"
: "${ZELO_PG_SERVICE:=postgres}"
: "${ZELO_DB_USER:=zelo}"
: "${TARGET_DB:=zelo_restore_check}"
[ -f /etc/zelo-backup.env ] && . /etc/zelo-backup.env

dump="${1:?usage: zelo-pg-restore.sh <dump.sql.gz> [target_db]}"
[ -n "${2:-}" ] && TARGET_DB="$2"
[ -f "$dump" ] || { echo "no such dump: $dump" >&2; exit 1; }

dc() { docker compose -f "$ZELO_COMPOSE_DIR/docker-compose.yml" exec -T "$ZELO_PG_SERVICE" "$@"; }

echo "[restore] (re)creating database ${TARGET_DB}"
dc psql -U "$ZELO_DB_USER" -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS ${TARGET_DB};" \
  -c "CREATE DATABASE ${TARGET_DB} OWNER ${ZELO_DB_USER};"

echo "[restore] loading ${dump} into ${TARGET_DB}"
gunzip -c "$dump" | dc psql -U "$ZELO_DB_USER" -d "$TARGET_DB" -v ON_ERROR_STOP=1 >/dev/null

echo "[restore] row counts in ${TARGET_DB} (a healthy restore shows non-zero audit_log):"
# Not ON_ERROR_STOP: a renamed table just prints an error rather than failing the drill.
dc psql -U "$ZELO_DB_USER" -d "$TARGET_DB" -tA -c \
  "select 'audit_log='||count(*) from audit_log
   union all select 'consent_events='||count(*) from consent_events
   union all select 'api_keys='||count(*) from api_keys
   union all select 'accounts='||count(*) from accounts;" || true

echo "[restore] OK. Drop the check DB with:"
echo "  docker compose -f ${ZELO_COMPOSE_DIR}/docker-compose.yml exec -T ${ZELO_PG_SERVICE} psql -U ${ZELO_DB_USER} -d postgres -c 'DROP DATABASE ${TARGET_DB};'"
