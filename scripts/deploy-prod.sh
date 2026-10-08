#!/usr/bin/env bash
#
# Выкатка прод-контура (compose.prod.yaml) на отдельной ВМ.
#
# Наследник deploy-stand-prod.sh, и разница между ними — в том, кто стоит
# перед шлюзом. На стенде порт шлюза держал внешний nginx соседа, и каждое
# пересоздание шлюза отдавало посетителю connection refused; отсюда весь
# порядок «шлюз трогаем последним». Здесь перед шлюзом стоит свой Caddy
# (сервис proxy), и он держит 80/443 всё время выкатки: пока шлюз
# пересоздаётся, посетитель видит 502 от Caddy на десятки секунд, а не
# отказ соединения. Порядок шагов тот же — он по-прежнему сводит окно
# к минимуму, — плюс шестой шаг: сам proxy, который compose пересоздаёт
# только при смене Caddyfile или образа.
#
# Запускается двумя способами, и оба — один и тот же файл:
#   - из GitHub Actions по SSH (.github/workflows/deploy-cloudru.yml) —
#     после пуша в main и ручного подтверждения в окружении production;
#   - руками на машине: /opt/vedal-portal/scripts/deploy-prod.sh main.
#
# Не делает: не восстанавливает базу, не правит .env, не переключает DNS.
# Сервис backup из compose.prod.yaml сознательно не поднимает
# (production_move_runbook.md, §3.3): копии снимает vedal-backup.timer.

set -euo pipefail

BRANCH="${1:-main}"

# Пути параметризованы ради проверяемости: прогнать скрипт на подставных
# путях с заглушками вместо docker и git — единственный способ проверить
# порядок шагов, не выкатывая ничего на боевую машину.
ROOT="${VEDAL_ROOT:-/opt/vedal-portal}"
LOCK_DIR="${VEDAL_VAR:-$ROOT/var}"
LOCK_FILE="$LOCK_DIR/deploy.lock"
LOG_FILE="$LOCK_DIR/deploy.log"
# Шлюз на петле: compose.prod.yaml публикует его на 127.0.0.1:18080
# ровно для этой проверки и для сторожа.
HEALTH_URL="${VEDAL_HEALTH_URL:-http://127.0.0.1:18080}"
COMPOSE_PROJECT="${VEDAL_COMPOSE_PROJECT:-vedal}"

WAIT_PORTAL="${VEDAL_WAIT_PORTAL:-300}"
WAIT_SITE="${VEDAL_WAIT_SITE:-420}"
WAIT_GATEWAY="${VEDAL_WAIT_GATEWAY:-180}"
WAIT_PROXY="${VEDAL_WAIT_PROXY:-60}"

# ————— перезапуск с копии —————
#
# Ниже идёт `git reset --hard`, а этот файл лежит в репозитории по тому же
# пути, по которому выполняется. bash читает скрипт по смещению в файле:
# подмена на ходу — выполнение середины нового файла с середины старого.
# Поэтому первым делом уходим в копию во временном каталоге.
if [ -z "${VEDAL_DEPLOY_REEXEC:-}" ]; then
  self_copy="$(mktemp -t vedal-deploy.XXXXXX)"
  cat "$0" >"$self_copy"
  chmod +x "$self_copy"
  export VEDAL_DEPLOY_REEXEC=1
  trap 'rm -f "$self_copy"' EXIT
  "$self_copy" "$@"
  exit $?
fi

mkdir -p "$LOCK_DIR"

exec 9>"$LOCK_FILE"
if ! flock -n 9; then
  echo "Другая выкатка VEDAL ещё идёт. Повторите позже."
  exit 75
fi

exec > >(tee -a "$LOG_FILE") 2>&1

# -p vedal: имя проекта задано явно, и это не вкусовщина. Без него compose
# берёт имя каталога первого файла (backend), и тома назывались бы
# backend_vedal-db; команда, набранная руками с -p vedal, завела бы
# ВТОРОЙ набор томов — пустую базу рядом с живой. Одно имя во всех
# командах runbook'а, в этом скрипте и в workflow.
COMPOSE=(docker compose --env-file "$ROOT/backend/.env" -p "$COMPOSE_PROJECT" \
  -f "$ROOT/backend/compose.yaml" \
  -f "$ROOT/backend/compose.prod.yaml" \
  --profile app)

say() { printf '%s  %s\n' "$(date -Is)" "$*"; }

wait_healthy() {
  local name="$1" limit="$2" started deadline state
  started=$(date +%s)
  deadline=$(( started + limit ))
  while :; do
    state=$(docker inspect \
      -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
      "$name" 2>/dev/null || echo missing)
    case "$state" in
      healthy|running)
        say "$name здоров за $(( $(date +%s) - started )) c"
        return 0
        ;;
      unhealthy|exited|dead)
        say "ОШИБКА: $name в состоянии $state"
        docker logs --tail 60 "$name" 2>&1 || true
        return 1
        ;;
    esac
    if [ "$(date +%s)" -ge "$deadline" ]; then
      say "ОШИБКА: $name не стал здоровым за $limit c (состояние: $state)"
      docker logs --tail 60 "$name" 2>&1 || true
      return 1
    fi
    sleep 3
  done
}

echo
say "==== VEDAL prod deploy ===="
say "branch: $BRANCH"

cd "$ROOT"

if [ ! -f backend/.env ]; then
  say "ОШИБКА: нет $ROOT/backend/.env — собрать по backend/.env.cloudru.example"
  exit 1
fi

git fetch origin
git checkout -q "$BRANCH"
git reset --hard "origin/$BRANCH"

"${COMPOSE[@]}" config --quiet

# ————— 1. сборка —————
#
# Отдельным шагом, до того как тронут хоть один контейнер: упавшая сборка
# сообщает «стек не тронут», а не оставляет гадать.
#
# BUILDX_NO_DEFAULT_ATTESTATIONS: без неё пересборка без единой правки даёт
# новый id образа (аттестация несёт время сборки), и compose пересоздавал
# бы шлюз и прокси на каждой выкатке. Разбор — deploy-stand-prod.sh.
export BUILDX_NO_DEFAULT_ATTESTATIONS=1

say "собираю образы (контейнеры не трогаю)"
if ! "${COMPOSE[@]}" build; then
  say "ОШИБКА: сборка не прошла — стек не тронут, работает прежняя версия"
  exit 1
fi

# Сервисы профиля app, которые поднимаются. backup из списка исключён
# намеренно (см. шапку); media-seed — профиль seed, сюда не входит.
APP_SERVICES=(db kafka keycloak connect connect-init portal api-gateway site proxy watchdog)

# ————— холодный старт —————
#
# Если прокси нет вовсе (машина после перезагрузки, стек снят руками,
# первый запуск), защищать нечего: порты и так никто не держит.
if ! docker inspect -f '{{.State.Running}}' vedal-proxy 2>/dev/null | grep -qx true; then
  say "прокси не запущен — холодный старт, поднимаю стек целиком"
  "${COMPOSE[@]}" up -d --remove-orphans "${APP_SERVICES[@]}"
  wait_healthy vedal-portal "$WAIT_PORTAL"
  wait_healthy vedal-site "$WAIT_SITE"
  wait_healthy vedal-gateway "$WAIT_GATEWAY"
  wait_healthy vedal-proxy "$WAIT_PROXY"
else
  # ————— 2. инфраструктура —————
  #
  # --no-deps здесь и дальше не украшение: без него compose тянет за собой
  # зависимые сервисы и возвращает каскад, ради устранения которого всё
  # это написано. --remove-orphans на тёплом пути намеренно нет.
  say "инфраструктура: db, kafka, keycloak, connect"
  "${COMPOSE[@]}" up -d --no-deps db kafka keycloak connect connect-init

  # ————— 3. портал —————
  say "портал: пересоздаю (прокси и шлюз живы)"
  "${COMPOSE[@]}" up -d --no-deps portal
  wait_healthy vedal-portal "$WAIT_PORTAL"

  # ————— 4. сайт —————
  say "сайт: пересоздаю (прокси и шлюз живы)"
  "${COMPOSE[@]}" up -d --no-deps site
  wait_healthy vedal-site "$WAIT_SITE"

  # ————— 5. шлюз —————
  gateway_before=$(docker inspect -f '{{.Id}}' vedal-gateway 2>/dev/null || echo none)
  say "шлюз: проверяю, нужно ли пересоздавать"
  "${COMPOSE[@]}" up -d --no-deps api-gateway
  gateway_after=$(docker inspect -f '{{.Id}}' vedal-gateway 2>/dev/null || echo none)
  if [ "$gateway_before" = "$gateway_after" ]; then
    say "шлюз не менялся — окна недоступности не было"
  else
    say "шлюз пересоздан — Caddy отвечал 502 на время старта Spring Boot"
    wait_healthy vedal-gateway "$WAIT_GATEWAY"
  fi

  # ————— 6. прокси и сторож —————
  #
  # Caddy пересоздаётся только при смене Caddyfile, переменных или
  # образа; в типичной выкатке — не трогается, и 80/443 не отпускает.
  proxy_before=$(docker inspect -f '{{.Id}}' vedal-proxy 2>/dev/null || echo none)
  say "прокси: проверяю, нужно ли пересоздавать"
  "${COMPOSE[@]}" up -d --no-deps proxy watchdog
  proxy_after=$(docker inspect -f '{{.Id}}' vedal-proxy 2>/dev/null || echo none)
  if [ "$proxy_before" = "$proxy_after" ]; then
    say "прокси не менялся — 80/443 не освобождались"
  else
    say "прокси пересоздан — секунды без ответа на 80/443"
    wait_healthy vedal-proxy "$WAIT_PROXY"
  fi
fi

# ————— проверка изнутри машины —————
#
# По 127.0.0.1, а не по домену: снаружи ответ смешан с состоянием канала
# и DNS, и здоровый стек выглядел бы упавшим из-за чужого маршрутизатора.
say "жду ответа шлюза на $HEALTH_URL/actuator/health"
deadline=$(( "$(date +%s)" + 240 ))
while :; do
  if curl -fsS --connect-timeout 3 --max-time 10 "$HEALTH_URL/actuator/health" >/dev/null; then
    break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    say "ОШИБКА: шлюз не ответил за 240 c"
    "${COMPOSE[@]}" ps
    exit 1
  fi
  sleep 5
done

# ————— проверка через Caddy и TLS —————
#
# --resolve подменяет DNS локально: запрос идёт по имени и с настоящим
# сертификатом, но на 127.0.0.1, минуя внешний канал. Проверяется ровно
# то, что видит посетитель: vhost, сертификат, цепочка proxy → шлюз → сайт.
#
# До переключения DNS сертификата ещё нет, и эта проверка обязана падать;
# на первой выкатке её отключают: VEDAL_DEPLOY_SKIP_TLS_CHECK=1.
domain=$(sed -n 's/^VEDAL_DOMAIN=//p' backend/.env | tail -1)
if [ "${VEDAL_DEPLOY_SKIP_TLS_CHECK:-0}" = 1 ]; then
  say "проверка TLS пропущена (VEDAL_DEPLOY_SKIP_TLS_CHECK=1)"
elif [ -n "$domain" ]; then
  say "проверяю https://$domain/ через Caddy на 127.0.0.1"
  code=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 30 \
    --resolve "$domain:443:127.0.0.1" "https://$domain/" || echo 000)
  if [ "$code" = 200 ]; then
    say "сайт по HTTPS отвечает 200"
  else
    say "ОШИБКА: https://$domain/ через Caddy ответил $code (сертификат? vhost? сайт?)"
    docker logs --tail 40 vedal-proxy 2>&1 || true
    exit 1
  fi
fi

"${COMPOSE[@]}" ps
say "health: $(curl -fsS "$HEALTH_URL/actuator/health")"
say "done: $(git rev-parse --short HEAD)"
