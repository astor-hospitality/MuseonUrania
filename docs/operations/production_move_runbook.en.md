# Moving to the production perimeter: step-by-step runbook

[Русский](production_move_runbook.md) · **English**

Issue [#45](https://github.com/michaelwelly/MuseonUrania/issues/45).
[production_move.md](production_move.en.md) answers "what to do" (a
separate VM, Managed PostgreSQL after the move, the network layout already
sitting in `compose.prod.yaml`) — this document answers "how, step by
step, without improvising on the spot." The decision to move and the date
are the owner's; this document takes effect once that decision is made.

Written to be read at 3 a.m. on move night and copied command by command,
not paraphrased.

## 1. `compose.prod.yaml` variables

The list below was not eyeballed — it comes from running the very
validation that the configuration refuses to pass without every one of
them:

```bash
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml \
  --profile app config
```

It adds variables to the error message one at a time, stopping at the
first missing one each run. The full list is the set with which the
command above finishes without an error:

```bash
grep -oE 'VEDAL_[A-Z0-9_]+:\?' backend/compose.prod.yaml | sort -u
```

**Do not try to bring this config up in full on the current VM.**
`--profile app up` without `config` starts `proxy` and takes over `80`/`443`
— the same ports held by `c3ag.ru`. Only `config` (generating the resulting
YAML) is safe to run there; `up` is not.

### 1.1. Required — `docker compose ... config` fails without these

| Variable | Used by | Value and where to get it |
| --- | --- | --- |
| `VEDAL_DOMAIN` | `proxy` (Caddy, site domain) | `vedal-med.ru`. The domain from #36. |
| `VEDAL_KEYCLOAK_DOMAIN` | `proxy` (Keycloak's own vhost) | `id.vedal-med.ru` — a subdomain, not a path on the main domain (its own cookie). |
| `VEDAL_MEDIA_DOMAIN` | `proxy` (CSP `img-src`) | `media.vedal-med.ru` — the host from `VEDAL_MEDIA_URL`, a CNAME to the bucket. |
| `VEDAL_DB_NAME` | `db`, `connect`, `portal` | `vedal`. Matches the current value — otherwise this is a new database, not a move. |
| `VEDAL_DB_USER` | `db`, `connect`, `portal` (migrations), `keycloak` | `vedal` — schema owner, the same role the dump was taken under on the old machine. |
| `VEDAL_DB_PASSWORD` | `db`, `connect`, `portal`, `keycloak` | A new schema-owner password on the new machine — it does not have to match the old one, it is an internal docker-network password. Generate with `openssl rand -base64 24`. |
| `VEDAL_RUNTIME_PASSWORD` | `db` (the `vedal_app` role init script), `portal` | A new runtime-role password. Also generated fresh — the role is created by the init script on the new machine's empty volume, the old password is not inherited. |
| `VEDAL_KEYCLOAK_URL` | `keycloak` (`KC_HOSTNAME`), `portal` (`VEDAL_OIDC_ISSUER`), `api-gateway`, `site` | `https://id.vedal-med.ru`. Must match the address the employee's browser actually visits — otherwise the portal rejects the token on `issuer`. |
| `VEDAL_KEYCLOAK_ADMIN` | `keycloak` (`KC_BOOTSTRAP_ADMIN_USERNAME`) | Login for Keycloak's own administrator (not the portal's). Pick a new one, do not carry it over from the stand. |
| `VEDAL_KEYCLOAK_ADMIN_PASSWORD` | `keycloak` | New password: `openssl rand -base64 24`. |
| `VEDAL_OIDC_SVC_CLIENT_SECRET` | `keycloak` (substituted into the realm on import), `portal` | New secret for the `vedal-portal-svc` service client: `openssl rand -base64 32`. The same value goes into **both** places — generate it once. |
| `VEDAL_S3_ACCESS_KEY` | `portal`, `media-seed` (`seed` profile, not part of `--profile app`) | Yandex Object Storage service-account key, `storage.editor` on both `vedal-media` and `vedal-documents`. The same key as now — storage is not moving. |
| `VEDAL_S3_SECRET_KEY` | `portal` | Secret for the key above. |
| `VEDAL_ALLOWED_ORIGINS` | `portal` (CORS) | `https://vedal-med.ru` — direct calls to the portal and local development are not part of this list. |
| `VEDAL_ADMIN_ORIGINS` | `portal` (admin CORS) | `https://vedal-med.ru`. |
| `VEDAL_PUBLIC_URL` | `portal` (`VEDAL_PORTAL_URL`), `api-gateway`, `site` (`NEXT_PUBLIC_API_URL`) | `https://vedal-med.ru` — the address the visitor's browser sees. |
| `VEDAL_MEDIA_URL` | `site` (`NEXT_PUBLIC_MEDIA_URL`) | `https://media.vedal-med.ru`. This value's host must match `VEDAL_MEDIA_DOMAIN` — let them drift apart and the CSP `img-src` blocks the photos while the page still loads fine (see the comment in `proxy/Caddyfile`). |

### 1.2. Not required, but skipping them turns the move into a surprise

Compose boots without these too — they fall back to built-in defaults that
are not fit for production.

| Variable | Default | Why set it on production anyway |
| --- | --- | --- |
| `VEDAL_ADMIN_ALLOW` | `private_ranges` | The right default for the `/admin` entry point — but this variable used to never reach the `proxy` container at all (see §4). It does now; if the plan is "VPN/bastion," no change needed. |
| `VEDAL_ACME_EMAIL` | `admin@localhost` | Same gap as above, fixed in §4. Set the team's real address — Let's Encrypt emails it if certificate renewal ever breaks. |
| `VEDAL_SITE_URL` (→ `NEXT_PUBLIC_SITE_URL`) | empty | **Critical for launch.** While empty, `robots.txt` blocks the whole site from indexing — that protects the stand's draft content, not what the live site needs. Set it to `https://vedal-med.ru` on the day the domain goes live. |
| `VEDAL_S3_BUCKET_MEDIA` / `VEDAL_S3_BUCKET_DOCUMENTS` | `vedal-media` / `vedal-documents` | Match the current buckets — nothing to move, but confirm explicitly rather than relying on the default. |
| `VEDAL_KEYCLOAK_DB_NAME` | `keycloak` | The Keycloak database's name inside the same PostgreSQL cluster. See §4 — this database does not create itself. |
| `VEDAL_BACKUP_KEEP_DAYS` | `7` | Only affects the `backup` service embedded in `compose.prod.yaml` — see §3.3 for why it should not be relied on. |
| `VEDAL_WATCH_MAIL_TO`, `SPRING_MAIL_HOST`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | empty | Without these the watchdog only writes alerts to its own log, which nobody reads on the server until something breaks — see [monitoring.md](monitoring.en.md). Set them before the move, not after the first incident. |
| `VEDAL_NOTIFICATIONS_MANAGER` | empty | Manager's address for lead notifications. |
| `VEDAL_GATEWAY_TRUSTED_PROXY` | docker-network ranges | The default is safe precisely because the docker network is unreachable from outside — leave it alone unless the network layout itself changes. |
| `VEDAL_METRIKA_ID` (→ `NEXT_PUBLIC_YANDEX_METRIKA_ID`) and `VEDAL_ANALYTICS_ORIGINS` | empty | Yandex Metrica, issue #53. Empty means no counter, and that is a legitimate state: the site does not depend on analytics. Fill them in **only together** and **only after the customer answers** question 12.11 in [PROJECT.en.md](../PROJECT.en.md): `VEDAL_METRIKA_ID=<id>`, `VEDAL_ANALYTICS_ORIGINS=https://mc.yandex.ru https://mc.yandex.com`. An id without the origins gives a counter that loaded and silently counts nothing — the security policy blocks its requests while the page stays intact. |

## 2. What to set up before day X

None of this happens on move night — if anything on this list is not
ready by day X, the date moves, speed does not compensate for it.

1. **New VM.** A Yandex Cloud instance separate from `smart_soultion_mvp`
   — no neighbor `c3ag.ru`. Docker and the Docker Compose plugin installed,
   the repository cloned into `/opt/vedal-portal` (following
   “vedal_vm_deploy_plan_2026-08-18.md” (`outputs/server/vedal_vm_deploy_plan_2026-08-18.md` — moved out of the public repository, see [the 8 September cleanup](../security/public_repo_cleanup_2026-09-08.md)), section 3).
2. **Security group.** `80` and `443` open to the whole internet (otherwise
   Caddy cannot get a certificate and no visitor can open the site). SSH
   — only from the team's exact IPs, on a non-standard port, same as on
   the old VM. **Nothing else** exposed: not `5434`, not `9092`, not
   `8083`, not a direct Keycloak port — that is the whole point of moving
   to `compose.prod.yaml` (see [production_move.md](production_move.en.md), §2.3).
3. **DNS.** Access to the `vedal-med.ru` zone confirmed. TTL on the
   records lowered ahead of time, at least a day before — otherwise a
   rollback is slower than the cutover itself (the same rule as in
   [domain_cutover_vedal_med_ru.md](domain_cutover_vedal_med_ru.en.md)).
   Current records captured verbatim — that is the rollback plan.
4. **An object-storage key with write access to `vedal-backups`.**
   Right now, per [backups.md](backups.en.md), the key on the machine can
   only read, and backup copies never leave the VM. On the new machine set
   `VEDAL_BACKUP_S3_BUCKET` and a key with the `storage.editor` role —
   otherwise the site move repeats the exact risk ("the VM died, so did the
   only copy") that it was supposed to remove.
5. **The Keycloak employee list.** Export it by hand from the old stand's
   Keycloak console (Users → export logins and roles) **before** shutting
   the old machine down. This is not a formality — see the warning in §4.2:
   employee accounts do not carry over automatically.
6. **Secrets prepared ahead of time**, not invented in a terminal at
   3 a.m.: `backend/.env` on the new machine assembled from the table in
   §1, values generated (`openssl rand -base64 24/32`), stored in the
   team's password manager.
7. **systemd units installed but not enabled**: `vedal-backup.timer`
   ([backups.md](backups.en.md)), `vedal-health.timer`
   ([monitoring.md](monitoring.en.md)), `vedal-autodeploy.timer`
   ([autodeploy.md](autodeploy.en.md)). Install with `sudo systemctl enable`
   without `--now`: enable only after the stack is up and checked, not
   before.
8. **`backend/keycloak/prod/vedal-realm.json` read through** — what
   clients and roles it defines, and that it only defines one technical
   service-account user (`service-account-vedal-portal-svc`) and
   deliberately **no staff account at all** (see §4.2).

## 3. Move day

### 3.1. Freeze the old machine

1. Announce that the portal is going into maintenance — the lead form and
   the admin UI are unavailable while data moves.
2. On the old VM, stop only the writing services, so the dump is taken
   from a database that is not moving under it:
   ```bash
   ssh -p 2222 ubuntu@51.250.31.97
   cd /opt/vedal-portal
   docker compose -f backend/compose.yaml -f backend/compose.stand-prod.yaml \
     --profile app stop portal api-gateway site
   ```
   The database (`vedal-db`) and Keycloak keep running — `pg_dump` reads
   without locking for writes, and Keycloak is not writing anything worth
   capturing here anyway (see §4.2 — its data does not move with this dump
   regardless).

### 3.2. Final dump

```bash
# on the old VM, run by hand — do not wait for the 3 a.m. timer
sudo systemctl start vedal-backup.service
journalctl -u vedal-backup -n 30 --no-pager
ls -la /var/backups/vedal/daily/   # take the newest *.dump
```

The run already verifies itself by restoring, and fails if the dump is
under 10 KB or the restored copy has fewer than 20 tables — if the command
above exits zero, the dump is fit to move (details in [backups.md](backups.en.md)).

Copy the dump to the new machine:

```bash
scp -P 2222 ubuntu@51.250.31.97:/var/backups/vedal/daily/vedal-<stamp>.dump \
  ./vedal-move.dump
scp -P <new-VM-port> ./vedal-move.dump ubuntu@<new-VM>:/tmp/vedal-move.dump
```

### 3.3. Bring up the environment on the new machine WITHOUT applications

The commands in this and the following sections rely on `$VEDAL_DB_USER`
and `$VEDAL_DB_NAME` from `backend/.env` — load the file into the
session's environment before copying them:

```bash
cd /opt/vedal-portal
set -a; source backend/.env; set +a
```

```bash
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml \
  up -d db kafka keycloak connect
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml logs -f db
```

Wait for `db` to become `healthy`. This is a **fresh** volume — on its
first start, `postgres` runs the init scripts
(`backend/db/init/10-runtime-role.sh` and `20-keycloak-db.sh`): they
create the `vedal_app` role and the `keycloak` database. Confirm both
actually exist **before** restoring the dump:

```bash
docker exec vedal-db psql -U "$VEDAL_DB_USER" -d postgres \
  -c "select rolname from pg_roles where rolname = 'vedal_app'"
docker exec vedal-db psql -U "$VEDAL_DB_USER" -d postgres \
  -c "select datname from pg_database where datname = 'keycloak'"
```

Both queries must return a non-empty row. If either comes back empty, the
volume was not actually fresh (a reused volume) — create the role/database
by hand, using the commands in the comments inside the init files
themselves, **before** going any further.

**Do not start the `backup` service from `compose.prod.yaml`.** It sits in
the `app` profile and will start on its own if the whole profile is
launched at once — but it is a separate mechanism, never exercised on this
stand: its own `pg_dump` loop once a day into its own docker volume, with
no restore verification and no off-machine copy (compare with
[backups.md](backups.en.md)). Running two independent backups of the same
database is not extra safety, it is a guaranteed "which dump do we trust"
argument on the day one is actually needed. Use `scripts/backup.sh` +
`vedal-backup.timer` as the single source of truth; leave the compose
`backup` service's container unstarted until a separate decision is made
about duplicating or removing it.

### 3.4. Restore the dump

```bash
docker exec -i vedal-db pg_restore -U "$VEDAL_DB_USER" -d "$VEDAL_DB_NAME" \
  --no-owner --role="$VEDAL_DB_USER" < /tmp/vedal-move.dump
```

`--no-owner` is mandatory: without it, `pg_restore` tries to assign
ownership to the role the dump was taken under on the old machine — which
happens to also be `vedal` (the variable was not changed, see §1.1), so in
practice the names line up, but do not rely on that coincidence:
`--no-owner` makes the restoring role — `VEDAL_DB_USER` on the new
machine, whatever it is — the owner instead.

Check row counts on a few key tables — not "restore exited without an
error" but "the data is the same":

```bash
docker exec vedal-db psql -U "$VEDAL_DB_USER" -d "$VEDAL_DB_NAME" \
  -c "select count(*) from lead" \
  -c "select count(*) from product" \
  -c "select count(*) from audit_entry"
```

Compare against the same query on the old machine (run it before the
freeze in §3.1, not after).

### 3.5. Bring up the applications

```bash
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml \
  --profile app up -d --build portal api-gateway site proxy watchdog
```

`backup` is deliberately left out of this command — see §3.3.

Wait for `portal`, `api-gateway`, `site` and `proxy` to report `healthy`
(`docker compose ps`) before switching DNS.

### 3.6. Recreate employees in Keycloak

The `prod` realm imports with zero staff accounts by design: the only
entry in `vedal-realm.json`'s user list is the technical service account
`service-account-vedal-portal-svc` (the comment in `compose.prod.yaml`
next to the `keycloak` volumes: "it does not have a single user in it —
employees are set up by whoever runs Keycloak, not by a file in the
repository"). The list from §2.5 is entered by hand through the new
Keycloak's console (`https://id.vedal-med.ru/admin/`, login —
`VEDAL_KEYCLOAK_ADMIN` from §1.1): Users → Add user, assign the
`portal-admin`/`portal-editor` roles, enable the mandatory second factor
on first login (the `prod` realm's policy requires it). Passwords are
temporary, the employee changes it on first login.

### 3.7. Switch DNS

```
vedal-med.ru.      A     <new VM's IP>
www.vedal-med.ru.  CNAME vedal-med.ru.
id.vedal-med.ru.   A     <new VM's IP>
```

`media.vedal-med.ru` is not touched — it already points at object storage
and stays that way regardless of which VM serves the site
([Caddyfile](../../backend/proxy/Caddyfile), the "media" section).

Check before waiting for full DNS propagation:

```bash
dig +short vedal-med.ru
curl -sv --resolve vedal-med.ru:443:<new VM's IP> https://vedal-med.ru/ -o /dev/null
```

The second `curl` spoofs DNS locally and checks the site by name before
real DNS actually propagates — the same trick used when the domain was
first connected (see [domain_cutover_vedal_med_ru.md](domain_cutover_vedal_med_ru.en.md),
step 1.4).

## 4. Pitfalls in moving the database

### 4.1. Format and version

The dump is taken with `pg_dump -Fc` (`scripts/backup.sh`) inside a
container running the `postgres:16` image — the same one pinned in
`backend/compose.yaml` for the new machine. As long as nobody changes the
image tag by hand, `pg_restore` of the same major version restores the
dump without surprises; a cross-version move (say, onto a future Managed
PostgreSQL running a different minor version) is out of scope for this
move — that step comes separately and after the site move, per
[production_move.md](production_move.en.md)'s recommendation. The project
uses zero PostgreSQL extensions (`create extension` appears nowhere) —
there is nothing to carry over besides the role and the data.

### 4.2. Keycloak — its data is not in the `vedal` database dump

This is the least obvious pitfall, and it is easy to miss precisely
because everything else "just works."

Right now (both on the local stack and on the current stand —
`compose.stand-prod.yaml` layers on top of `compose.yaml`, not
`compose.prod.yaml`) Keycloak runs `start-dev` **without** `KC_DB` — that
is, on its built-in H2 file database inside the `vedal-keycloak` volume.
Every employee account, its password, its second factor and the realm
settings live in that file, not in PostgreSQL.

`compose.prod.yaml` switches Keycloak to `KC_DB: postgres`, a separate
`keycloak` database in the same cluster — **a different store**, not a
migration of the old one. Neither dumping/restoring the `vedal` database
nor copying the `vedal-keycloak` volume moves this data: the new Keycloak
starts against an empty `keycloak` database and only imports what sits in
`backend/keycloak/prod/vedal-realm.json` — clients, roles, and one
technical service-account user for `vedal-portal-svc`, but **no staff
account at all** (see §3.6).

Two consequences follow:

- the employee list is collected ahead of time (§2.5) and re-entered by
  hand after the move (§3.6) — this is manual work, not part of restoring
  the dump;
- if a second factor is already enabled for someone through Keycloak
  before the move, that also has to be set up again — there are no
  recovery codes saved in the realm file, nor could there be.

### 4.3. The `vedal_app` role — order matters

The dump carries not just data but also the `GRANT`s that migrations
V15/V16 handed to the `vedal_app` role at the moment the dump was taken
(`pg_dump` without `--no-acl` keeps privileges). Restoring the dump
**before** `vedal_app` exists on the new machine makes `pg_restore` hit a
`GRANT ... TO vedal_app` for a role that is not there yet — by default
`pg_restore` does not stop on such errors (no `--exit-on-error`), it just
skips those statements and moves on, and that is the dangerous part: the
restore "succeeds," while the runtime role ends up missing part of its
privileges, and the only thing that reports it is an exit code nobody is
watching.

Hence the strict order in §3.3 → §3.4: bring `db` up to `healthy` first
(the role and the Keycloak database are already created by the init
scripts), only then `pg_restore`. The check in §3.3 ("both queries must
return a non-empty row") is exactly the guard against restoring in the
wrong order.

Separately: if the eventual target is Managed PostgreSQL (the next step
after this move, see [production_move.md](production_move.en.md), §2.2),
the schema owner there has no `CREATEROLE`, and `10-runtime-role.sh` will
not run at all — the role on a managed cluster is created through the
console or the `yc` CLI **before** running migrations, already documented
as a precondition in the V16 migration's own comment.

## 5. Verification after the switch

The checklist below comes from the run protocol in
[issue #57](https://github.com/michaelwelly/MuseonUrania/issues/57#issuecomment-5573863111)
— the same list already used to accept the stand, applied here to the live
domain. Check from **outside**, not from inside the new VM — unlike the
stand run, here what matters is exactly what a real visitor sees through
real DNS and a real certificate.

| Check | How |
| --- | --- |
| Site opens over HTTPS | `curl -svo /dev/null https://vedal-med.ru/` — `200`, valid certificate |
| `c3ag.ru` unaffected | A separate request to the old VM — it is not part of the new setup at all, but confirm nobody touched it |
| Lead from the site | Submit a test lead, confirm it shows up in the admin UI (`З-2026-xxxx`) |
| Admin login | `https://vedal-med.ru/admin/` → Keycloak form at `https://id.vedal-med.ru` → dashboard → sign out |
| Vedalina answers about a product | Ask about a specific model, get an answer with source links |
| Publish a news item | Admin UI: create a draft → publish → appears in `/api/public/v1/news` → unpublish → delete |
| Documents | Downloading a published one — `200`; a private one — `404` and an entry in the audit log (`document.access.denied`) |
| Photos | `200` on product cards, bucket listing stays closed |
| `robots.txt` | **Not** `Disallow: /` — unlike the stand, the live site must be open to indexing (see `VEDAL_SITE_URL` in §1.2) |
| Audit log: visitor address | `audit_entry` shows the real client IP, not `proxy`'s address (the same bug already fixed for the gateway — [#58](https://github.com/michaelwelly/MuseonUrania/issues/58)) |
| `/admin` entry point | A request from outside the trusted range gets `403` with "The edit entry point is only available from a trusted network" |
| Watchdog sends alerts | Temporarily lower `VEDAL_WATCH_FAILS` or stop `portal` for 5 minutes, confirm an email lands at `VEDAL_WATCH_MAIL_TO` |
| New machine's backup | `sudo systemctl start vedal-backup.service` by hand, confirm the dump is taken and passes restore verification |

## 6. Rollback

The old VM is **not** shut down until the checks in §5 pass — at least a
day after the switch, longer if DNS has not propagated everywhere yet.

1. Restore the DNS records captured in §2.3, verbatim.
2. Wait out the old TTL (that is what lowering it ahead of time was for).
3. On the old VM, bring back what was stopped in §3.1:
   ```bash
   docker compose -f backend/compose.yaml -f backend/compose.stand-prod.yaml \
     --profile app start portal api-gateway site
   ```
4. Leads and edits made on the new machine during the switch window are
   **lost** on rollback — the old machine's database knows nothing about
   them. If the window between the switch and the rollback decision was
   more than a few minutes, pull a dump of leads/news from that window off
   the new machine and merge it by hand rather than rolling back blind.
5. The new VM is not deleted — fix it and repeat the switch, rather than
   starting the data move over from scratch.

## Related documents

- [Migration to the Cloud.ru VM: step by step](cloudru_migration_runbook.en.md) —
  the same move, but from a dump onto an empty machine, after the old VM died.
- [Moving to the production perimeter: analysis and recommendation](production_move.en.md).
- [Backups](backups.en.md).
- [Connecting the vedal-med.ru domain](domain_cutover_vedal_med_ru.en.md).
- [Stand-prod perimeter](stand_prod_perimeter.en.md).
- [Monitoring](monitoring.en.md).
- [Stand autodeploy](autodeploy.en.md).
- “VM deploy plan” (`outputs/server/vedal_vm_deploy_plan_2026-08-18.md` — moved out of the public repository, see [the 8 September cleanup](../security/public_repo_cleanup_2026-09-08.md)).
