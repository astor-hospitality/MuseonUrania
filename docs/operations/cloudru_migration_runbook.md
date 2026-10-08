# Переезд на ВМ Cloud.ru: порядок действий

**Русский** · [English](cloudru_migration_runbook.en.md)

Старая ВМ Yandex Cloud (`51.250.31.97`) мертва. Новая площадка — Cloud.ru
Evolution: Ubuntu 24.04, 4 vCPU / 16 ГБ, 100 ГБ SSD, публичный адрес
`176.123.165.162` (может ещё поменяться — ниже он везде `<IP>`), пользователь
`ubuntu`, SSH на `22`. Docker на ней не стоит.

Документ — продолжение [production_move_runbook.md](production_move_runbook.md):
тот писался для живой старой машины и переезда «с горячего», этот — для
переезда с дампа на пустую ВМ. Таблицы переменных (§1 там) и подводные камни
базы (§4 там) не повторяются — на них ссылки. Рассчитан на то, что команды
копируют целиком.

Что переезжает и что нет:

| Что | Куда |
| --- | --- |
| База `vedal` | дамп `pg_dump -Fc` (26 секций TABLE DATA) → контейнер `db` новой ВМ |
| Keycloak: realm, клиенты, роли | из `backend/keycloak/prod/vedal-realm.json` при первом старте |
| Keycloak: учётные записи сотрудников | **не переезжают** — бэкапа не было; заводятся заново (§7) |
| Объектное хранилище (`vedal-media`, `vedal-documents`, `vedal-backups`) | остаётся в Yandex Object Storage, меняется только ключ в `.env` |
| Домены `vedal-med.ru`, `id.vedal-med.ru` | A-записи на `<IP>` (§8); `media.vedal-med.ru` — не трогается |
| `c3ag.ru` (Astor_Butler_MVP) | на эту же ВМ, за общим Caddy (§10) |

Одно имя compose-проекта во всех командах — `-p vedal`. Без него compose
называет тома по каталогу первого файла (`backend_vedal-db`), и команда,
набранная с `-p`, завела бы второй набор томов — пустую базу рядом с живой.
Поэтому везде ниже:

```bash
cd /opt/vedal-portal
alias vc='docker compose --env-file backend/.env -p vedal -f backend/compose.yaml -f backend/compose.prod.yaml'
```

## 1. До начала

- Дамп на руках, проверен `pg_restore --list` (26 секций TABLE DATA).
- Доступ к зоне `vedal-med.ru` на nic.ru; TTL A-записей понижен до 300 **за
  сутки** (§8). Текущие записи сняты дословно.
- Ключ Yandex Object Storage с ролью `storage.editor` на три бакета.
- Ключ YandexGPT (если Ведалина работала через него) — из менеджера паролей.
- Список сотрудников и их ролей (`portal-admin` / `portal-sales` /
  `portal-production`) — восстановить по памяти и переписке: в Keycloak их
  больше нет.
- Секреты сгенерированы и сохранены в менеджере паролей (§3).
- SSH-ключ ed25519 только для деплоя из GitHub Actions (§9).

## 2. Первая загрузка ВМ

Скрипт идемпотентен: оборвался SSH — запустить снова.

```bash
ssh ubuntu@<IP>
sudo bash -c 'curl -fsSL https://raw.githubusercontent.com/astor-hospitality/MuseonUrania/main/scripts/bootstrap-cloudru.sh | bash'
```

Что он делает — в шапке [scripts/bootstrap-cloudru.sh](../../scripts/bootstrap-cloudru.sh):
apt, Docker CE + compose plugin из репозитория Docker, `ubuntu` в группе
`docker`, ufw (`22`, `80`, `443`), sysctl, своп 2 ГБ, docker-сеть `edge`,
клон репозитория в `/opt/vedal-portal`, каталоги `/var/backups/vedal` и
`/var/lib/vedal`, systemd-юниты бэкапа и сторожа (включены, **не**
запущены), unattended-upgrades без автоперезагрузки.

После — выйти и зайти по SSH заново (группа `docker`), и сразу закрыть
вход по паролю:

```bash
sudo sed -i 's/^#\?PasswordAuthentication .*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo systemctl reload ssh
docker ps      # работает без sudo
```

Если решено сменить порт SSH: `Port <новый>` в `sshd_config`, затем
`sudo SSH_PORT=<новый> /opt/vedal-portal/scripts/bootstrap-cloudru.sh`
(откроет новый в ufw), проверить вход, и только потом `sudo ufw delete allow 22/tcp`.

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

Образец — [backend/.env.cloudru.example](../../backend/.env.cloudru.example):
каждая переменная с комментарием, откуда её брать. Смысл каждой —
[production_move_runbook.md](production_move_runbook.md), §1.1–1.2. Проверка
полноты (падает на первой недостающей):

```bash
vc --profile app config --quiet && echo ok
```

## 4. База: поднять `db`, восстановить дамп

Сначала только `db` — не `kafka`, не `connect`, не `keycloak`. На пустом
томе `postgres` выполнит init-скрипты: роль `vedal_app` и база `keycloak`.
Восстанавливать **до** них нельзя ([production_move_runbook.md](production_move_runbook.md), §4.3).

```bash
vc up -d db
vc logs -f db        # дождаться «database system is ready to accept connections», Ctrl-C
docker inspect -f '{{.State.Health.Status}}' vedal-db   # healthy
```

Дамп — на машину и в базу:

```bash
scp -P 22 ./vedal-move.dump ubuntu@<IP>:/tmp/vedal-move.dump
scripts/restore-db.sh /tmp/vedal-move.dump --dry-run   # только проверки
scripts/restore-db.sh /tmp/vedal-move.dump
```

[scripts/restore-db.sh](../../scripts/restore-db.sh) проверяет дамп и
контейнер, требует, чтобы `portal`/`connect`/`gateway` не работали,
снимает слот репликации Debezium, пересоздаёт базу, делает `pg_restore
--no-owner --role=$VEDAL_DB_USER --exit-on-error`, считает таблицы (порог 20)
и печатает число строк в **каждой** таблице. Эти числа — сверить с тем, что
известно о старой базе (хотя бы `lead`, `product`, `audit_entry`).

Повторный прогон безопасен: база пересоздаётся с нуля, а не дописывается.

## 5. Поднять стек

```bash
vc --profile app up -d db kafka keycloak connect connect-init portal api-gateway site proxy watchdog
vc --profile app ps
```

`backup` в списке нет сознательно ([production_move_runbook.md](production_move_runbook.md),
§3.3): копии снимает `vedal-backup.timer`. Порядок — compose сам ждёт
`healthy` по `depends_on`; портал накатывает миграции (минуты), сайт —
собирает страницы на старте.

Дальше выкатки идут через [scripts/deploy-prod.sh](../../scripts/deploy-prod.sh)
(руками или из GitHub Actions, §9); он же — проверенный способ «поднять всё
после перезагрузки»:

```bash
VEDAL_DEPLOY_SKIP_TLS_CHECK=1 scripts/deploy-prod.sh main   # до DNS — без проверки TLS
```

### Проверки изнутри машины

```bash
curl -fsS http://127.0.0.1:18080/actuator/health          # {"status":"UP"}
curl -fsS http://127.0.0.1:18080/api/public/v1/products | head -c 300
# Caddy и vhost по имени, минуя DNS. До переключения DNS сертификата ещё
# нет, и -k здесь временно: проверяется маршрут, не TLS.
curl -sk --resolve vedal-med.ru:443:127.0.0.1 https://vedal-med.ru/ -o /dev/null -w '%{http_code}\n'
curl -sk --resolve id.vedal-med.ru:443:127.0.0.1 https://id.vedal-med.ru/realms/vedal -o /dev/null -w '%{http_code}\n'
docker logs --tail 30 vedal-proxy      # ошибки ACME здесь ожидаемы до DNS
```

Шлюз на `127.0.0.1:18080` — петля, снаружи порта нет
(`compose.prod.yaml`, комментарий у `api-gateway`); сторож и деплой
ходят по нему.

## 6. Keycloak: администратор

Keycloak 26 по `VEDAL_KEYCLOAK_ADMIN`/`VEDAL_KEYCLOAK_ADMIN_PASSWORD` заводит
на пустой базе **временного** администратора realm `master`. Консоль
доступна только по домену (`KC_HOSTNAME`), то есть после §8 — либо раньше,
через SSH-туннель и подмену hosts, что сложнее, чем подождать DNS.

После переключения DNS:

1. `https://id.vedal-med.ru/admin/` → войти временным администратором.
2. Realm `master` → Users → Add user: постоянный администратор (свой логин,
   пароль из менеджера паролей, Email verified). Role mapping → `admin`.
3. Выйти, войти постоянным, временного удалить. Keycloak напоминает об этом
   баннером, пока временный существует.
4. Realm `vedal` на месте: Clients — `vedal-admin-ui`, `vedal-portal`,
   `vedal-portal-svc`; Realm roles — `portal-admin`, `portal-sales`,
   `portal-production`, `portal-mfa-required`. Users — только
   `service-account-vedal-portal-svc`.

## 7. Keycloak: сотрудники заново

Учётных записей нет и взять их неоткуда — заводятся по списку из §1.
Через консоль: realm `vedal` → Users → Add user (username, email, имя;
Email verified) → Credentials → Set password, **Temporary: On** →
Role mapping → Assign role → `portal-admin` или `portal-sales` /
`portal-production`. Второй фактор realm потребует при первом входе
(политика realm, [mfa_rollout.md](mfa_rollout.md)); старые коды
восстановления недействительны — у всех заново.

То же из терминала машины, для списка из нескольких человек
(`kcadm.sh` есть в образе; пароль администратора спрашивается, а не
передаётся аргументом — так он не остаётся в истории оболочки):

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

Временный пароль сотруднику — по каналу, которому доверяют, не в рабочий чат.

## 8. DNS: переключение на `<IP>`

За сутки до: TTL A-записей `vedal-med.ru` и `id.vedal-med.ru` → `300`.
Старые значения записаны.

В день X, на nic.ru:

```
vedal-med.ru.      A      <IP>
www.vedal-med.ru.  CNAME  vedal-med.ru.
id.vedal-med.ru.   A      <IP>
```

`media.vedal-med.ru` — CNAME на бакет, не трогать. MX/SPF/DKIM — не трогать:
почта живёт на `mail.vedal-med.ru`, отдельный хост.

Проверка:

```bash
dig +short vedal-med.ru @8.8.8.8          # <IP>
dig +short id.vedal-med.ru @8.8.8.8       # <IP>
# как только DNS разошёлся, Caddy получит сертификаты сам — минуты:
docker logs --tail 50 vedal-proxy | grep -i 'certificate'
curl -svo /dev/null https://vedal-med.ru/ 2>&1 | grep -E 'HTTP/|subject:|expire'
curl -svo /dev/null https://id.vedal-med.ru/realms/vedal 2>&1 | grep -E 'HTTP/'
```

Дальше — §6 и §7 (Keycloak), затем приёмка по таблице
[production_move_runbook.md](production_move_runbook.md), §5 (сайт, заявка,
вход в админку, Ведалина, документы, фотографии, `robots.txt` без
`Disallow: /`, адрес клиента в `audit_entry`, дверь `/admin` отвечает 403
снаружи).

**Откат** — пока старая ВМ мертва, откатывать некуда: возврат A-записей на
`51.250.31.97` даёт «сайт не отвечает» вместо нынешнего «сайт не
отвечает». Поэтому низкий TTL здесь нужен не для отката, а для скорости
переключения и для смены `<IP>`, если Cloud.ru выдаст другой адрес.
Если же сайт заказчика где-то ещё поднят на старом хостинге — вернуть
сохранённые записи дословно, TTL отработает за 5 минут.

## 9. Выкатка из GitHub Actions

На стенде выкатку делал таймер на машине, опрашивавший `main`
([autodeploy.md](autodeploy.md)); причины — публичный репозиторий и чужой
боевой сайт на той же ВМ. Здесь ВМ своя, и выкатка идёт обычным путём:
[.github/workflows/deploy-cloudru.yml](../../.github/workflows/deploy-cloudru.yml)
заходит по SSH и запускает `scripts/deploy-prod.sh`. Почему это приемлемо
теперь и чем закрыты прежние риски — в шапке workflow: секреты в окружении
`production` с обязательным рецензентом, раннер GitHub'овский, на машине
выполняется один скрипт из репозитория, `permissions: contents: read`,
очередь выкаток без обрыва. `vedal-autodeploy.timer` на эту машину **не**
ставится.

Настройка, один раз:

```bash
# на своей машине: ключ только для деплоя
ssh-keygen -t ed25519 -N '' -C vedal-deploy -f ~/.ssh/vedal-deploy
ssh-copy-id -i ~/.ssh/vedal-deploy.pub -p 22 ubuntu@<IP>
ssh-keyscan -p 22 <IP>          # → CLOUDRU_SSH_KNOWN_HOSTS
```

GitHub → Settings → Environments → New environment `production`:
Required reviewers — те, кто подтверждает выкатку; Deployment branches —
`main`. Environment secrets:

| Секрет | Значение |
| --- | --- |
| `CLOUDRU_SSH_HOST` | `<IP>` |
| `CLOUDRU_SSH_PORT` | `22` |
| `CLOUDRU_SSH_USER` | `ubuntu` |
| `CLOUDRU_SSH_KEY` | содержимое `~/.ssh/vedal-deploy` (приватный) |
| `CLOUDRU_SSH_KNOWN_HOSTS` | вывод `ssh-keyscan` |

Первый запуск — руками: Actions → «Выкатка на Cloud.ru» → Run workflow
(`main`) → Approve. Дальше каждый пуш в `main` ждёт подтверждения в той же
вкладке. CI (`ci.yml`) на тот же пуш идёт параллельно — рецензент смотрит
на его результат, прежде чем нажать Approve.

Откат — `git revert` в `main` и подтверждение выкатки: след остаётся в
истории.

## 10. Общий прокси: `c3ag.ru` рядом с VEDAL

Порты `80`/`443` на ВМ держит один процесс — Caddy стека VEDAL (контейнер
`vedal-proxy`). Соседний проект не поднимает своего прокси и не публикует
портов; его контейнеры входят в общую внешнюю docker-сеть `edge`, и Caddy
находит их по имени.

Контракт для Astor_Butler_MVP (и позже бэкенда Astor):

1. В его compose:
   ```yaml
   services:
     web:
       container_name: c3ag-web      # по этому имени Caddy его находит
       # ports: — НЕ публиковать; 3000 остаётся внутри сети
       networks: [default, edge]
   networks:
     edge:
       name: edge
       external: true
   ```
2. vhost — файлом на машине в `/opt/edge/sites.d/c3ag.ru.caddy`. Образец с
   заголовками и `reverse_proxy c3ag-web:3000` —
   [backend/proxy/sites.d/c3ag.ru.caddy.example](../../backend/proxy/sites.d/c3ag.ru.caddy.example);
   там же блок `api.c3ag.ru → astor-api:8080` на будущее. Файл живёт в
   репозитории Astor и ставится оттуда — сюда pull request не нужен.
3. Применить без перезапуска стека VEDAL:
   ```bash
   docker exec vedal-proxy caddy validate --config /etc/caddy/Caddyfile
   docker exec vedal-proxy caddy reload --config /etc/caddy/Caddyfile
   ```
4. DNS `c3ag.ru`, `www.c3ag.ru`, `api.c3ag.ru` → `<IP>`; сертификаты Caddy
   возьмёт сам. Пока DNS не указывает сюда, блок в файле безвреден: ACME
   не удаётся, остальные хосты работают.

Что это **не** меняет в VEDAL: в `edge` входит только `proxy`; `db`,
`kafka`, `keycloak`, `portal`, `api-gateway` остаются в сети проекта
`vedal` и соседу по имени не видны. CSP, HSTS и дверь `/admin` — свойства
блока `{$VEDAL_DOMAIN}` в `Caddyfile` и на чужие хосты не распространяются;
что сосед ставит себе — его файл. Сеть создаёт `bootstrap-cloudru.sh`
(`docker network create edge`), compose её внешней считает и на `down`
не удаляет.

Деплой соседа (`docker compose up -d` в его каталоге) `vedal-proxy` не
трогает: Caddy резолвит `c3ag-web` на каждый запрос, пересозданный
контейнер подхватывается сам.

## 11. Таймеры и приёмка

После §5–§8 и приёмки:

```bash
sudo systemctl start vedal-backup.timer vedal-health.timer
systemctl list-timers 'vedal-*'
sudo systemctl start vedal-backup.service && journalctl -u vedal-backup -n 20 --no-pager
sudo systemctl start vedal-health.service && journalctl -u vedal-health -n 10 --no-pager
ls -la /var/backups/vedal/daily/
```

Бэкап должен снять дамп, восстановить его в проверочную базу, насчитать
≥ 20 таблиц и выгрузить в `vedal-backups` (строка «выгружено»; если
«выгрузка не настроена» — у ключа нет права записи в этот бакет, см.
[backups.md](backups.md)).

## Связанные документы

- [Переезд в прод-контур: порядок действий](production_move_runbook.md) —
  переменные, подводные камни базы, таблица приёмки.
- [Автодеплой стенда](autodeploy.md) — что заменил §9.
- [Резервные копии](backups.md), [Мониторинг](monitoring.md).
- [Подключение домена vedal-med.ru](domain_cutover_vedal_med_ru.md).
- [Ввод второго фактора](mfa_rollout.md).
