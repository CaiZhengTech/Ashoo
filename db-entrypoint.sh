#!/bin/sh
set -e

# Fly mounts the persistent volume at /data owned by root:root. PostgreSQL runs
# as the unprivileged 'postgres' user and cannot create its data directory there,
# so we fix ownership (and pre-create PGDATA) as root before handing off to the
# stock postgres entrypoint, which runs initdb on first boot.
mkdir -p "$PGDATA"
chown -R postgres:postgres /data

exec docker-entrypoint.sh "$@"
