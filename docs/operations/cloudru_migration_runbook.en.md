# Migration to the Cloud.ru VM: step by step

[Русский](cloudru_migration_runbook.md) · **English**

The old Yandex Cloud VM (`51.250.31.97`) is dead. The new host is Cloud.ru
Evolution: Ubuntu 24.04, 4 vCPU / 16 GB, 100 GB SSD, public address
`176.123.165.162` (may still change — below it is `<IP>` everywhere), user
`ubuntu`, SSH on `22`. Docker is not installed on it.

This document continues [production_move_runbook.en.md](production_move_runbook.en.md):
that one was written for a live old machine and a "hot" move, this one for
a move from a dump onto an empty VM. The variable tables (§1 there) and the
database pitfalls (§4 there) are not repeated — they are linked. Written to be
copied command by command.

What moves and what does not:

| What | Where |
| --- | --- |
| Database `vedal` | `pg_dump -Fc` dump (26 TABLE DATA sections) → container `db` on the new VM |
| Keycloak: realm, clients, roles | from `backend/keycloak/prod/vedal-realm.json` on first start |
| Keycloak: staff accounts | **do not move** — there was no backup; recreated (§7) |
| Object storage (`vedal-media`, `vedal-documents`, `vedal-backups`) | stays in Yandex Object Storage, only the key in `.env` changes |
| Domains `vedal-med.ru`, `id.vedal-med.ru` | A records to `<IP>` (§8); `media.vedal-med.ru` is untouched |
| `c3ag.ru` (Astor_Butler_MVP) | onto the same VM, behind the shared Caddy (§10) |

One compose project name in every command — `-p vedal`. Without it compose
names volumes after the directory of the first file (`backend_vedal-db`),
and a command typed with `-p` would create a second set of volumes — an empty
database next to the live one. Hence everywhere below:

```bash
cd /opt/vedal-portal
alias vc='docker compose --env-file backend/.env -p vedal -f backend/compose.yaml -f backend/compose.prod.yaml'
```

## 1. Before you start

- The dump is at hand and checked with `pg_restore --list` (26 TABLE DATA sections).
- Access to the `vedal-med.ru` zone at nic.ru; TTL of the A records lowered
  to 300 **a day in advance** (§8). Current records saved verbatim.
- A Yandex Object Storage key with the `storage.editor` role on all three buckets.
- The YandexGPT key (if Vedalina ran through it) — from the team password manager.
- The list of staff and their roles (`portal-admin` / `portal-sales` /
  `portal-production`) — reconstruct it from memory and correspondence:
  Keycloak no longer has them.
- Secrets generated and stored in the password manager (§3).
- An ed25519 SSH key used only for deploys from GitHub Actions (§9).

## 2. First boot of the VM

The script is idempotent: if SSH drops, run it again.

```bash
ssh ubuntu@<IP>
sudo bash -c 'curl -fsSL https://raw.githubusercontent.com/astor-hospitality/MuseonUrania/main/scripts/bootstrap-cloudru.sh | bash'
```

What it does is in the header of [scripts/bootstrap-cloudru.sh](../../scripts/bootstrap-cloudru.sh):
apt, Docker CE + compose plugin from Docker's repository, `ubuntu` in the
`docker` group, ufw (`22`, `80`, `443`), sysctl, 2 GB swap, the `edge` docker
network, a clone of the repository into `/opt/vedal-portal`, the
`/var/backups/vedal` and `/var/lib/vedal` directories, the backup and
health systemd units (enabled, **not** started), unattended-upgrades without
automatic reboot.

Afterwards log out and back in over SSH (the `docker` group), and close
password login right away:

```bash
sudo sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo systemctl reload ssh
docker ps      # works without sudo
```

If you decide to change the SSH port: `Port <new>` in `sshd_config`, then
`sudo SSH_PORT=<new> /opt/vedal-portal/scripts/bootstrap-cloudru.sh`
(opens the new one in ufw), verify login, and only then `sudo ufw delete allow 22/tcp`.

## 3. `backend/.env`

```bash
cd /opt/vedal-portal
cp backend/.env.cloudru.example backend/.env
chmod 600 backend/.env
openssl rand -base64 24   # VEDAL_DB_PASSWORD
openssl rand -base64 24   # VEDAL_RUNTIME_PASSWORD
openssl rand -base64 24   # VEDAL_KEYCLOAK_ADMIN_PASSWORD
openssl rand -base64 32   # VEDAL_OIDC_SVC_CLIENT_SECRET
nano backend/.env
```

The template is [backend/.env.cloudru.example](../../backend/.env.cloudru.example):
every variable with a comment on where to take it from. What each one means —
[production_move_runbook.en.md](production_move_runbook.en.md), §1.1–1.2.
Completeness check (fails on the first missing one):

```bash
vc --profile app config --quiet && echo ok
```

## 4. Database: start `db`, restore the dump

`db` only at first — not `kafka`, not `connect`, not `keycloak`. On an empty
volume `postgres` runs the init scripts: the `vedal_app` role and the
`keycloak` database. Restoring **before** them is not allowed
([production_move_runbook.en.md](production_move_runbook.en.md), §4.3).

```bash
vc up -d db
vc logs -f db        # wait for «database system is ready to accept connections», Ctrl-C
docker inspect -f '{{.State.Health.Status}}' vedal-db   # healthy
```

The dump — onto the machine and into the database:

```bash
scp -P 22 ./vedal-move.dump ubuntu@<IP>:/tmp/vedal-move.dump
scripts/restore-db.sh /tmp/vedal-move.dump --dry-run   # checks only
scripts/restore-db.sh /tmp/vedal-move.dump
```

[scripts/restore-db.sh](../../scripts/restore-db.sh) checks the dump and
the container, requires `portal`/`connect`/`gateway` to be stopped, drops
the Debezium replication slot, recreates the database, runs `pg_restore
--no-owner --role=$VEDAL_DB_USER --exit-on-error`, counts tables (threshold
20) and prints the row count of **every** table. Compare those numbers with
what is known about the old database (at least `lead`, `product`,
`audit_entry`).

Re-running is safe: the database is recreated from scratch, not appended to.

## 5. Bring the stack up

```bash
vc --profile app up -d db kafka keycloak connect connect-init portal api-gateway site proxy watchdog
vc --profile app ps
```

`backup` is deliberately absent from the list
([production_move_runbook.en.md](production_move_runbook.en.md), §3.3): copies
are taken by `vedal-backup.timer`. Ordering — compose itself waits for
`healthy` via `depends_on`; the portal applies migrations (minutes), the site
builds pages on start.

Further rollouts go through [scripts/deploy-prod.sh](../../scripts/deploy-prod.sh)
(by hand or from GitHub Actions, §9); it is also the tested way to "bring
everything back after a reboot":

```bash
VEDAL_DEPLOY_SKIP_TLS_CHECK=1 scripts/deploy-prod.sh main   # before DNS — without the TLS check
```

### Checks from inside the machine

```bash
curl -fsS http://127.0.0.1:18080/actuator/health          # {"status":"UP"}
curl -fsS http://127.0.0.1:18080/api/public/v1/products | head -c 300
# Caddy and the vhost by name, bypassing DNS. Before the DNS switch there is
# no certificate yet, so -k is temporary: this checks the route, not TLS.
curl -sk --resolve vedal-med.ru:443:127.0.0.1 https://vedal-med.ru/ -o /dev/null -w '%{http_code}\n'
curl -sk --resolve id.vedal-med.ru:443:127.0.0.1 https://id.vedal-med.ru/realms/vedal -o /dev/null -w '%{http_code}\n'
docker logs --tail 30 vedal-proxy      # ACME errors here are expected until DNS
```

The gateway on `127.0.0.1:18080` is loopback only, the port is not
reachable from outside (`compose.prod.yaml`, comment at `api-gateway`); the
health timer and the deploy script use it.

## 6. Keycloak: the administrator

From `VEDAL_KEYCLOAK_ADMIN`/`VEDAL_KEYCLOAK_ADMIN_PASSWORD` Keycloak 26 creates
a **temporary** administrator of the `master` realm on an empty database. The
console is reachable only through the domain (`KC_HOSTNAME`), that is after
§8 — or earlier via an SSH tunnel and a hosts override, which is more
trouble than waiting for DNS.

After the DNS switch:

1. `https://id.vedal-med.ru/admin/` → sign in as the temporary administrator.
2. Realm `master` → Users → Add user: the permanent administrator (own login,
   password from the password manager, Email verified). Role mapping → `admin`.
3. Sign out, sign in as the permanent one, delete the temporary one. Keycloak
   shows a banner about it while the temporary one exists.
4. Realm `vedal` is in place: Clients — `vedal-admin-ui`, `vedal-portal`,
   `vedal-portal-svc`; Realm roles — `portal-admin`, `portal-sales`,
   `portal-production`, `portal-mfa-required`. Users — only
   `service-account-vedal-portal-svc`.

## 7. Keycloak: staff accounts from scratch

There are no accounts and nowhere to take them from — they are created from the
list in §1. Through the console: realm `vedal` → Users → Add user (username,
email, name; Email verified) → Credentials → Set password, **Temporary: On** →
Role mapping → Assign role → `portal-admin` or `portal-sales` /
`portal-production`. The realm demands the second factor on first login
(realm policy, [mfa_rollout.en.md](mfa_rollout.en.md)); old recovery codes
are void — everyone sets it up again.

The same from the machine's terminal, for a list of several people
(`kcadm.sh` ships in the image; the administrator password is prompted for,
not passed as an argument — so it does not stay in shell history):

```bash
docker exec -it vedal-keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master --user "$VEDAL_KEYCLOAK_ADMIN"

docker exec vedal-keycloak /opt/keycloak/bin/kcadm.sh create users -r vedal \
  -s username=i.ivanov -s email=i.ivanov@vedal-med.ru -s firstName=Иван -s lastName=Иванов \
  -s enabled=true -s emailVerified=true
docker exec -it vedal-keycloak /opt/keycloak/bin/kcadm.sh set-password -r vedal \
  --username i.ivanov --temporary
docker exec vedal-keycloak /opt/keycloak/bin/kcadm.sh add-roles -r vedal \
  --uusername i.ivanov --rolename portal-admin
```

The temporary password goes to the employee through a trusted channel, not
the work chat.

## 8. DNS: switching to `<IP>`

A day before: TTL of the A records `vedal-med.ru` and `id.vedal-med.ru` → `300`.
Old values written down.

On day X, at nic.ru:

```
vedal-med.ru.      A      <IP>
www.vedal-med.ru.  CNAME  vedal-med.ru.
id.vedal-med.ru.   A      <IP>
```

`media.vedal-med.ru` is a CNAME to the bucket — do not touch. MX/SPF/DKIM —
do not touch: mail lives on `mail.vedal-med.ru`, a separate host.

Check:

```bash
dig +short vedal-med.ru @8.8.8.8          # <IP>
dig +short id.vedal-med.ru @8.8.8.8       # <IP>
# as soon as DNS has propagated Caddy obtains the certificates itself — minutes:
docker logs --tail 50 vedal-proxy | grep -i 'certificate'
curl -svo /dev/null https://vedal-med.ru/ 2>&1 | grep -E 'HTTP/|subject:|expire'
curl -svo /dev/null https://id.vedal-med.ru/realms/vedal 2>&1 | grep -E 'HTTP/'
```

Then — §6 and §7 (Keycloak), then acceptance by the table in
[production_move_runbook.en.md](production_move_runbook.en.md), §5 (site,
lead, admin login, Vedalina, documents, photos, `robots.txt` without
`Disallow: /`, client address in `audit_entry`, the `/admin` door answers
403 from outside).

**Rollback** — while the old VM is dead there is nothing to roll back to:
pointing the A records back at `51.250.31.97` yields "site down" instead of
the current "site down". So the low TTL here is not for rollback but for
switching speed and for changing `<IP>` if Cloud.ru hands out a different
address. If the customer's site is still up somewhere on the old hosting —
restore the saved records verbatim; the TTL expires in 5 minutes.

## 9. Deploying from GitHub Actions

On the stand a timer on the machine polled `main`
([autodeploy.en.md](autodeploy.en.md)); the reasons were the public repository
and a foreign production site on the same VM. Here the VM is ours and the
deploy goes the usual way:
[.github/workflows/deploy-cloudru.yml](../../.github/workflows/deploy-cloudru.yml)
logs in over SSH and runs `scripts/deploy-prod.sh`. Why this is acceptable
now and how the earlier risks are closed is in the workflow header: secrets
live in the `production` environment with a required reviewer, the runner is
GitHub's, one script from the repository runs on the machine,
`permissions: contents: read`, deploys queue instead of interrupting each
other. `vedal-autodeploy.timer` is **not** installed on this machine.

Set-up, once:

```bash
# on your own machine: a key only for deploys
ssh-keygen -t ed25519 -N '' -C vedal-deploy -f ~/.ssh/vedal-deploy
ssh-copy-id -i ~/.ssh/vedal-deploy.pub -p 22 ubuntu@<IP>
ssh-keyscan -p 22 <IP>          # → CLOUDRU_SSH_KNOWN_HOSTS
```

GitHub → Settings → Environments → New environment `production`:
Required reviewers — those who approve a deploy; Deployment branches —
`main`. Environment secrets:

| Secret | Value |
| --- | --- |
| `CLOUDRU_SSH_HOST` | `<IP>` |
| `CLOUDRU_SSH_PORT` | `22` |
| `CLOUDRU_SSH_USER` | `ubuntu` |
| `CLOUDRU_SSH_KEY` | contents of `~/.ssh/vedal-deploy` (private) |
| `CLOUDRU_SSH_KNOWN_HOSTS` | output of `ssh-keyscan` |

First run by hand: Actions → «Выкатка на Cloud.ru» → Run workflow (`main`) →
Approve. From then on every push to `main` waits for approval in the same
tab. CI (`ci.yml`) runs in parallel on the same push — the reviewer looks at
its result before pressing Approve.

Rollback — `git revert` on `main` and approve the deploy: the trace stays in
history.

## 10. The shared proxy: `c3ag.ru` next to VEDAL

Ports `80`/`443` on the VM are held by one process — the Caddy of the VEDAL
stack (container `vedal-proxy`). The neighbouring project does not run its
own proxy and publishes no ports; its containers join the shared external
docker network `edge`, and Caddy finds them by name.

The contract for Astor_Butler_MVP (and later the Astor backend):

1. In its compose:
   ```yaml
   services:
     web:
       container_name: c3ag-web      # Caddy finds it by this name
       # ports: — do NOT publish; 3000 stays inside the network
       networks: [default, edge]
   networks:
     edge:
       name: edge
       external: true
   ```
2. The vhost — as a file on the machine at `/opt/edge/sites.d/c3ag.ru.caddy`.
   A sample with headers and `reverse_proxy c3ag-web:3000` —
   [backend/proxy/sites.d/c3ag.ru.caddy.example](../../backend/proxy/sites.d/c3ag.ru.caddy.example);
   it also has the `api.c3ag.ru → astor-api:8080` block for later. The file
   lives in the Astor repository and is installed from there — no pull
   request here is needed.
3. Apply without restarting the VEDAL stack:
   ```bash
   docker exec vedal-proxy caddy validate --config /etc/caddy/Caddyfile
   docker exec vedal-proxy caddy reload --config /etc/caddy/Caddyfile
   ```
4. DNS `c3ag.ru`, `www.c3ag.ru`, `api.c3ag.ru` → `<IP>`; Caddy obtains the
   certificates itself. While DNS does not point here the block in the file
   is harmless: ACME fails, the other hosts keep working.

What this does **not** change in VEDAL: only `proxy` joins `edge`; `db`,
`kafka`, `keycloak`, `portal`, `api-gateway` stay in the `vedal` project
network and are not visible to the neighbour by name. CSP, HSTS and the
`/admin` door are properties of the `{$VEDAL_DOMAIN}` block in the
`Caddyfile` and do not extend to foreign hosts; what the neighbour sets for
itself is its own file. The network is created by `bootstrap-cloudru.sh`
(`docker network create edge`); compose treats it as external and does not
remove it on `down`.

The neighbour's deploy (`docker compose up -d` in its directory) does not
touch `vedal-proxy`: Caddy resolves `c3ag-web` on every request, a recreated
container is picked up by itself.

## 11. Timers and acceptance

After §5–§8 and acceptance:

```bash
sudo systemctl start vedal-backup.timer vedal-health.timer
systemctl list-timers 'vedal-*'
sudo systemctl start vedal-backup.service && journalctl -u vedal-backup -n 20 --no-pager
sudo systemctl start vedal-health.service && journalctl -u vedal-health -n 10 --no-pager
ls -la /var/backups/vedal/daily/
```

The backup must take a dump, restore it into a check database, count
≥ 20 tables and upload to `vedal-backups` (the line «выгружено»; if it says
«выгрузка не настроена» the key has no write permission on that bucket, see
[backups.en.md](backups.en.md)).

## Related documents

- [Production move: step by step](production_move_runbook.en.md) —
  variables, database pitfalls, acceptance table.
- [Stand autodeploy](autodeploy.en.md) — what §9 replaced.
- [Backups](backups.en.md), [Monitoring](monitoring.en.md).
- [Connecting the vedal-med.ru domain](domain_cutover_vedal_med_ru.en.md).
- [MFA rollout](mfa_rollout.en.md).
