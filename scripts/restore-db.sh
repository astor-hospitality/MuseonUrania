#!/usr/bin/env bash
#
# Восстановление дампа базы VEDAL (pg_dump -Fc) в контейнер db
# прод-стека (compose.prod.yaml). Шаг переезда площадки; документ —
# docs/operations/cloudru_migration_runbook.md, §4.
#
#   scripts/restore-db.sh /tmp/vedal-move.dump --dry-run   # только проверки
#   scripts/restore-db.sh /tmp/vedal-move.dump             # восстановить
#
# Что делает, по порядку:
#
#   1. Читает backend/.env — имена базы и владельца берутся оттуда,
#      а не повторяются здесь.
#   2. Проверяет дамп: файл есть, не меньше 10 КБ, pg_restore --list
#      его разбирает; считает секции TABLE DATA.
#   3. Проверяет контейнер db: запущен и healthy; роль рантайма и база
#      Keycloak заведены init-скриптами (runbook §4.3: восстанавливать
#      ДО роли нельзя — GRANT'ы для несуществующей роли pg_restore
#      молча пропустит).
#   4. Требует, чтобы пишущие в базу контейнеры (portal, connect) не
#      работали: база сейчас будет удалена и создана заново.
#   --dry-run останавливается здесь и печатает, что сделал бы.
#   5. Снимает слот логической репликации Debezium, если он есть:
#      базу с активным слотом PostgreSQL удалить не даёт.
#   6. DROP DATABASE + CREATE DATABASE владельцем VEDAL_DB_USER.
#   7. pg_restore --no-owner --role=VEDAL_DB_USER. Код возврата pg_restore
#      не глотается молча: предупреждения считаются и показываются.
#   8. Проверяет: таблиц в public не меньше 20, печатает число строк
#      в КАЖДОЙ таблице — сверять с цифрами, снятыми на старой машине.
#
# Почему drop/create, а не restore поверх. Повторный прогон (дамп не тот,
# восстановление оборвалось) поверх наполовину заполненной базы даёт
# дубликаты и ошибки уникальности, которые pg_restore по умолчанию
# пропускает. Чистая база — единственное состояние, из которого результат
# восстановления предсказуем. Цена — скрипт нельзя запускать на живой базе
# с данными, которых нет в дампе; проверка §4 это и охраняет.

set -euo pipefail

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

DUMP=""
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    -h|--help) usage ;;
    -*) echo "неизвестный ключ: $arg" >&2; usage ;;
    *) DUMP="$arg" ;;
  esac
done
[ -n "$DUMP" ] || usage

ROOT="${VEDAL_ROOT:-/opt/vedal-portal}"
ENV_FILE="${VEDAL_ENV_FILE:-$ROOT/backend/.env}"

say() { printf '==> %s\n' "$*"; }
die() { printf 'ОШИБКА: %s\n' "$*" >&2; exit 1; }

# ————— 1. окружение —————

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
else
  say "файла $ENV_FILE нет — беру VEDAL_DB_* из окружения"
fi

DB_CONTAINER="${VEDAL_DB_CONTAINER:-vedal-db}"
DB_NAME="${VEDAL_DB_NAME:?задайте VEDAL_DB_NAME (в backend/.env)}"
DB_USER="${VEDAL_DB_USER:?задайте VEDAL_DB_USER (в backend/.env)}"
RUNTIME_USER="${VEDAL_RUNTIME_USER:-vedal_app}"
KEYCLOAK_DB="${VEDAL_KEYCLOAK_DB_NAME:-keycloak}"
MIN_TABLES="${VEDAL_RESTORE_MIN_TABLES:-20}"

psql_db() {
  # $1 — база, дальше — аргументы psql. -tA: голые значения, без рамок.
  local db="$1"; shift
  docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$db" -v ON_ERROR_STOP=1 -tA "$@"
}

# ————— 2. дамп —————

say "дамп: $DUMP"
[ -f "$DUMP" ] || die "файла нет"
size=$(stat -c %s "$DUMP" 2>/dev/null || stat -f %z "$DUMP")
[ "$size" -ge 10000 ] || die "дамп меньше 10 КБ ($size байт) — это не копия базы"
echo "   размер: $((size / 1024)) КБ"

# Оглавление дампа разбирает pg_restore из того же образа, что и база:
# подать файл в контейнер потоком, ничего не восстанавливая (--list).
toc=$(docker exec -i "$DB_CONTAINER" pg_restore --list < "$DUMP" 2>&1) \
  || die "pg_restore --list не смог прочитать дамп: ${toc:0:300}"
table_data=$(printf '%s\n' "$toc" | grep -c ' TABLE DATA ' || true)
dump_db=$(printf '%s\n' "$toc" | sed -n 's/^;\s*dbname: //p' | head -1)
dump_ver=$(printf '%s\n' "$toc" | sed -n 's/^;\s*Dumped from database version: //p' | head -1)
echo "   из базы: ${dump_db:-?}, PostgreSQL ${dump_ver:-?}, секций TABLE DATA: $table_data"
[ "$table_data" -ge "$MIN_TABLES" ] \
  || die "в дампе только $table_data секций TABLE DATA (ожидалось не меньше $MIN_TABLES) — он неполный"

# ————— 3. контейнер db —————

say "контейнер $DB_CONTAINER"
state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
  "$DB_CONTAINER" 2>/dev/null || echo missing)
[ "$state" = healthy ] || die "контейнер в состоянии «$state», нужен healthy: docker compose ... up -d db"
server_ver=$(psql_db postgres -c "show server_version")
echo "   PostgreSQL $server_ver"

role_ok=$(psql_db postgres -c "select count(*) from pg_roles where rolname = '$RUNTIME_USER'")
[ "$role_ok" = 1 ] || die "роли $RUNTIME_USER нет — init-скрипт не отработал (том не был пустым?). Завести руками, см. backend/db/init/10-runtime-role.sh"
kc_ok=$(psql_db postgres -c "select count(*) from pg_database where datname = '$KEYCLOAK_DB'")
[ "$kc_ok" = 1 ] || die "базы $KEYCLOAK_DB нет — init-скрипт не отработал. См. backend/db/init/20-keycloak-db.sh"
echo "   роль $RUNTIME_USER и база $KEYCLOAK_DB на месте"

# ————— 4. никто не пишет —————

for writer in vedal-portal vedal-connect vedal-gateway; do
  if docker inspect -f '{{.State.Running}}' "$writer" 2>/dev/null | grep -qx true; then
    die "контейнер $writer запущен — остановить перед восстановлением: docker compose ... stop portal api-gateway connect"
  fi
done
echo "   portal, connect, gateway не запущены"

existing=$(psql_db postgres -c "select count(*) from pg_database where datname = '$DB_NAME'")
if [ "$existing" = 1 ]; then
  cur_tables=$(psql_db "$DB_NAME" -c "select count(*) from information_schema.tables where table_schema = 'public'")
  conns=$(psql_db postgres -c "select count(*) from pg_stat_activity where datname = '$DB_NAME' and pid <> pg_backend_pid()")
  slots=$(psql_db postgres -c "select string_agg(slot_name, ', ') from pg_replication_slots where database = '$DB_NAME'")
  echo "   база $DB_NAME есть: таблиц $cur_tables, соединений $conns, слотов репликации: ${slots:-нет}"
else
  echo "   базы $DB_NAME нет — будет создана"
fi

if [ "$DRY_RUN" = 1 ]; then
  say "dry-run: дальше было бы — снять слоты, DROP/CREATE DATABASE $DB_NAME, pg_restore --no-owner --role=$DB_USER, проверка"
  exit 0
fi

# ————— 5–6. чистая база —————

say "пересоздаю базу $DB_NAME"
if [ "$existing" = 1 ]; then
  # Слот логической репликации привязан к базе: пока он есть, DROP DATABASE
  # отвечает «is used by an active logical replication slot». Debezium
  # заведёт его заново при регистрации коннектора (connect-init);
  # snapshot.mode=no_data — старые события повторно не уедут.
  psql_db postgres -c "select pg_drop_replication_slot(slot_name) from pg_replication_slots where database = '$DB_NAME'" >/dev/null
  psql_db postgres -c "select pg_terminate_backend(pid) from pg_stat_activity where datname = '$DB_NAME' and pid <> pg_backend_pid()" >/dev/null
  psql_db postgres -c "drop database \"$DB_NAME\""
fi
psql_db postgres -c "create database \"$DB_NAME\" owner \"$DB_USER\""

# ————— 7. восстановление —————
#
# --no-owner: владельцем объектов становится подключившаяся роль
# (VEDAL_DB_USER новой машины), а не та, под которой снимали дамп.
# ACL при этом восстанавливаются: GRANT'ы для vedal_app из миграций
# V15/V16 едут вместе с данными — ради этого и проверка §3.
#
# --exit-on-error: первая же ошибка останавливает восстановление, а не
# пропускается (runbook §4.3 — именно так терялись GRANT'ы). Если дамп
# упирается во что-то заведомо безобидное, прогнать ещё раз с
# VEDAL_RESTORE_LENIENT=1: тогда pg_restore идёт до конца, а число
# ошибок печатается ниже — и его надо прочитать, а не принять.
#
# Вывод pg_restore — в файл, код возврата — в переменную: глотать его
# «|| true» значит не узнать, что именно пропущено.
say "pg_restore в $DB_NAME"
log=$(mktemp -t vedal-restore.XXXXXX)
strict=(--exit-on-error)
[ "${VEDAL_RESTORE_LENIENT:-0}" = 1 ] && strict=()
rc=0
docker exec -i "$DB_CONTAINER" pg_restore -U "$DB_USER" -d "$DB_NAME" \
  --no-owner --role="$DB_USER" "${strict[@]}" < "$DUMP" >"$log" 2>&1 || rc=$?
if [ "$rc" -ne 0 ] && [ "${#strict[@]}" -gt 0 ]; then
  echo "   pg_restore завершился с кодом $rc; последние строки журнала ($log):"
  tail -n 20 "$log" | sed 's/^/   | /'
  die "восстановление не прошло — база $DB_NAME оставлена как есть для разбора"
fi
errors=$(grep -c 'pg_restore: error' "$log" || true)
warnings=$(grep -ci 'warning' "$log" || true)
if [ "$errors" -gt 0 ]; then
  echo "   ВНИМАНИЕ: ошибок pg_restore пропущено: $errors (журнал: $log) — прочитать каждую:"
  grep 'pg_restore: error' "$log" | head -n 10 | sed 's/^/   | /'
else
  echo "   восстановлено без ошибок; предупреждений: $warnings (журнал: $log)"
fi

# ————— 8. проверка —————

say "проверка"
tables=$(psql_db "$DB_NAME" -c "select count(*) from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'")
[ "$tables" -ge "$MIN_TABLES" ] \
  || die "в восстановленной базе только $tables таблиц (порог $MIN_TABLES) — дамп восстановился не целиком"
echo "   таблиц в public: $tables"

# Число строк точное, не оценка из pg_class: после восстановления
# статистика пуста, и reltuples показал бы нули.
echo "   строк по таблицам:"
psql_db "$DB_NAME" -F ' ' -c "
  select format('%-32s %s', table_name,
    (xpath('/row/n/text()',
       query_to_xml(format('select count(*) as n from %I.%I', table_schema, table_name), false, true, '')))[1]::text)
  from information_schema.tables
  where table_schema = 'public' and table_type = 'BASE TABLE'
  order by table_name" | sed 's/^/      /'

grants=$(psql_db "$DB_NAME" -c "select count(*) from information_schema.role_table_grants where grantee = '$RUNTIME_USER'")
echo "   прав у $RUNTIME_USER на таблицы: $grants"
[ "$grants" -gt 0 ] || echo "   ВНИМАНИЕ: у роли рантайма нет ни одного GRANT — портал не сможет работать. Миграции V15/V16 выдадут их при старте только если ещё не применены; иначе выдать руками."

say "готово: $DB_NAME восстановлена из $(basename "$DUMP")"
echo "   сверьте числа выше с снятыми на старой машине, затем: docker compose ... --profile app up -d"
