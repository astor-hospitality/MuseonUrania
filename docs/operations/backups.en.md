# Backups

[Русский](backups.md) · **English**

## What gets copied

The database only. Leads, clients, deals, quotes, conversations and the audit
log exist in exactly one place — PostgreSQL. Document files live in object
storage with its own redundancy; images and settings are rebuilt from git.

The second database is `keycloak`, when it exists in the same cluster (as in
`compose.prod.yaml`). It holds staff accounts, passwords and second factors,
and the `vedal` dump does not contain them. On 3 November 2026, after the move
to Cloud.ru, the `vedal` realm turned out to have zero staff, and there was
nothing to restore them from. On the stand (`start-dev`) there is no separate
database: accounts live in an H2 file inside the `vedal-keycloak` volume, and
the script logs that it does not copy them.

## How it works

`scripts/backup.sh`, run by `vedal-backup.timer` every night at three o'clock
machine time.

One run does four things:

1. Takes a dump in `custom` format (`pg_dump -Fc`) into `/var/backups/vedal/daily/`.
2. **Verifies it by restoring** — unpacks it into a temporary database next to
   the live one and counts tables and leads. A copy nobody has ever restored is
   a hope, not a backup: a corrupted dump looks like a file of the right size
   right up to the day it is needed.
3. If a `keycloak` database exists, dumps it too into `keycloak-<time>.dump`.
   It checks the dump's table of contents (it must contain `user_entity` data)
   and logs how many staff the `vedal` realm has. Zero staff does not fail the
   run, but prints a prominent warning.
4. On Mondays, sets the copies aside in `weekly/`.
5. Trims the old ones: seven daily, four weekly, separately for each database.

The run fails with a non-zero code if the dump is under ten kilobytes, the
restored database has fewer than twenty tables, or the `keycloak` dump has no
user table. A backup that fails silently is
worse than no backup: people rely on it.

## What is still missing

**Off-machine copies.** The script can upload dumps to a bucket, but that needs
a key with write permission — the one on the VM today can only read. Without the
upload, losing the machine means losing both the database and its copies.

Enabled by two variables in `backend/.env`:

```
VEDAL_BACKUP_S3_BUCKET=vedal-backups
VEDAL_S3_ACCESS_KEY=<key with the storage.editor role>
VEDAL_S3_SECRET_KEY=<...>
```

## Installing on the machine

```bash
sudo cp /opt/vedal-portal/scripts/vedal-backup.service /etc/systemd/system/
sudo cp /opt/vedal-portal/scripts/vedal-backup.timer /etc/systemd/system/
sudo mkdir -p /var/backups/vedal && sudo chown ubuntu:ubuntu /var/backups/vedal
sudo systemctl daemon-reload
sudo systemctl enable --now vedal-backup.timer
```

To check right away, without waiting for the night:

```bash
sudo systemctl start vedal-backup.service
journalctl -u vedal-backup -n 30 --no-pager
```

## Restoring

```bash
# see what is there
ls -la /var/backups/vedal/daily/

# restore into a separate database and confirm it holds what you expect
docker exec vedal-db psql -U vedal -d postgres -c "CREATE DATABASE vedal_restore"
docker exec -i vedal-db pg_restore -U vedal -d vedal_restore --no-owner < <dump>
docker exec vedal-db psql -U vedal -d vedal_restore -c "select count(*) from lead"
```

Replacing the live database with a restored one is a separate decision, not a
step in a runbook: the portal must be stopped, and the current state dumped
before the replacement — however broken it looks.

## Settings

| Variable | Default | What it does |
| --- | --- | --- |
| `VEDAL_BACKUP_DIR` | `/var/backups/vedal` | where to store dumps |
| `VEDAL_BACKUP_KEEP_DAILY` | `7` | how many daily copies to keep |
| `VEDAL_BACKUP_KEEP_WEEKLY` | `4` | how many weekly copies to keep |
| `VEDAL_BACKUP_S3_BUCKET` | empty | bucket for off-machine copies |
| `VEDAL_DB_CONTAINER` | `vedal-db` | name of the database container |
| `VEDAL_KEYCLOAK_DB_NAME` | `keycloak` | Keycloak database name; if it is absent, only `vedal` is copied |
