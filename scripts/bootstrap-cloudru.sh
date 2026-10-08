#!/usr/bin/env bash
#
# Первая загрузка ВМ Cloud.ru Evolution под VEDAL Portal (Ubuntu 24.04).
#
# Запускается один раз от root по SSH на чистой машине — и сколько угодно
# раз потом: каждый шаг проверяет, сделан ли он уже, и не делает заново.
# Это свойство здесь главное. Скрипт, который нельзя перезапустить после
# оборвавшегося SSH, превращает первую загрузку в ручной разбор «что успело».
#
#   ssh ubuntu@<адрес>
#   sudo bash -c 'curl -fsSL https://raw.githubusercontent.com/astor-hospitality/MuseonUrania/main/scripts/bootstrap-cloudru.sh | bash'
#
# или, если репозиторий уже под рукой:
#
#   sudo ./scripts/bootstrap-cloudru.sh
#
# Что делает, по порядку: обновляет пакеты, ставит Docker CE с плагином
# compose из репозитория Docker (не из Ubuntu — там он отстаёт на версии
# и без compose v2), пускает `ubuntu` в группу docker, закрывает файрволом
# всё, кроме SSH, 80 и 443, выставляет несколько sysctl, заводит внешнюю
# docker-сеть `edge` для общего прокси, клонирует репозиторий в
# /opt/vedal-portal, готовит каталоги для копий и состояния, ставит
# systemd-юниты бэкапа и сторожа (включёнными, но НЕ запущенными — таймеры
# запускаются после того, как стек поднят и проверен), включает
# автоматические обновления безопасности.
#
# Чего НЕ делает, намеренно: не заполняет backend/.env, не поднимает стек,
# не трогает DNS. Это шаги человека по docs/operations/cloudru_migration_runbook.md.
#
# Все адреса и имена — параметрами с умолчаниями: публичный IP ВМ ещё
# может поменяться, и прошивать его здесь незачем.

set -euo pipefail

# ————— параметры —————

VEDAL_REPO="${VEDAL_REPO:-https://github.com/astor-hospitality/MuseonUrania.git}"
VEDAL_BRANCH="${VEDAL_BRANCH:-main}"
VEDAL_ROOT="${VEDAL_ROOT:-/opt/vedal-portal}"
VEDAL_USER="${VEDAL_USER:-ubuntu}"
# Порт SSH. 22 на первой загрузке; если позже переводится на нестандартный —
# перезапустить скрипт с SSH_PORT=<новый>: ufw откроет новый, старый
# закрывается руками, когда новый проверен.
SSH_PORT="${SSH_PORT:-22}"
# Общая сеть прокси с соседними проектами. То же имя — в backend/.env
# (VEDAL_EDGE_NETWORK) и в compose соседа.
EDGE_NETWORK="${EDGE_NETWORK:-edge}"
EDGE_SITES_DIR="${EDGE_SITES_DIR:-/opt/edge/sites.d}"
BACKUP_DIR="${VEDAL_BACKUP_DIR:-/var/backups/vedal}"
# Своп. 16 ГБ памяти стеку хватает с запасом, но без свопа убийца по
# нехватке памяти начинает с самого жирного процесса — а это Kafka или
# портал. Два гигабайта — подушка, а не память: 0 отключает шаг.
SWAP_GB="${SWAP_GB:-2}"

say() { printf '\n==> %s\n' "$*"; }

if [ "$(id -u)" -ne 0 ]; then
  echo "Запускать от root: sudo $0" >&2
  exit 1
fi

if ! grep -qs 'Ubuntu' /etc/os-release; then
  echo "Рассчитан на Ubuntu 24.04; здесь другая система — остановлюсь." >&2
  exit 1
fi

if ! id "$VEDAL_USER" >/dev/null 2>&1; then
  echo "Пользователя $VEDAL_USER нет; задайте VEDAL_USER=<логин>." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive

# ————— 1. пакеты —————

say "обновляю пакеты"
apt-get update -q
apt-get upgrade -y -q
apt-get install -y -q \
  ca-certificates curl gnupg git ufw unattended-upgrades \
  apt-transport-https lsb-release

# ————— 2. Docker CE из репозитория Docker —————
#
# Ключ и список источников кладутся заново только если их нет: повторный
# запуск не должен переписывать работающую настройку apt.

say "Docker CE"
install -m 0755 -d /etc/apt/keyrings
if [ ! -f /etc/apt/keyrings/docker.asc ]; then
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
fi
if [ ! -f /etc/apt/sources.list.d/docker.list ]; then
  # shellcheck source=/dev/null
  codename="$(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")"
  printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu %s stable\n' \
    "$(dpkg --print-architecture)" "$codename" > /etc/apt/sources.list.d/docker.list
  apt-get update -q
fi
if ! command -v docker >/dev/null 2>&1; then
  apt-get install -y -q docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin
else
  echo "   docker уже стоит: $(docker --version)"
fi

# Ротация логов для контейнеров, у которых она не задана в compose
# (свои сервисы compose.prod.yaml ограничивает сам; это — для соседей).
# Файл создаётся только если его нет: чужую настройку демона не трогаем.
if [ ! -f /etc/docker/daemon.json ]; then
  cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "5" }
}
JSON
fi

systemctl enable --now docker >/dev/null
if ! id -nG "$VEDAL_USER" | tr ' ' '\n' | grep -qx docker; then
  usermod -aG docker "$VEDAL_USER"
  echo "   $VEDAL_USER добавлен в группу docker (вступит в силу при новом входе по SSH)"
fi

# ————— 3. файрвол —————
#
# Наружу: SSH, 80, 443 — и ничего больше. База, Kafka, Keycloak, Connect
# и порты приложений в compose.prod.yaml не публикуются вовсе, так что
# правило здесь — второй слой, а не единственный.
#
# Честно про docker и ufw: опубликованный docker'ом порт (`ports:` без
# 127.0.0.1) попадает в nftables правилом DNAT, которое ufw не видит.
# Поэтому периметр держится на compose.prod.yaml, где публикуется только
# proxy; ufw прикрывает всё, что слушает сам хост.

say "ufw: ${SSH_PORT}/tcp, 80/tcp, 443/tcp"
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow "${SSH_PORT}/tcp" comment 'SSH' >/dev/null
ufw allow 80/tcp comment 'HTTP: ACME и редирект на HTTPS' >/dev/null
ufw allow 443/tcp comment 'HTTPS' >/dev/null
# --force: без вопроса «продолжить?», который по SSH-пайпу некому ответить.
ufw --force enable >/dev/null
ufw status numbered | sed 's/^/   /'

# ————— 4. sysctl —————

say "sysctl"
cat > /etc/sysctl.d/90-vedal.conf <<'CONF'
# Своп — подушка, а не память: трогать его ядру стоит в последнюю очередь.
vm.swappiness = 10
# Очередь входящих соединений на слушающий сокет: Caddy, шлюз и Kafka
# принимают всплески, и умолчание 4096 на свежем ядре уже стоит,
# но на старых образах — 128.
net.core.somaxconn = 4096
# Kafka и JVM держат много открытых файлов и отображений памяти.
fs.file-max = 1048576
vm.max_map_count = 262144
CONF
sysctl -q --system >/dev/null

# ————— 5. своп —————

if [ "$SWAP_GB" -gt 0 ] && [ ! -f /swapfile ]; then
  say "своп ${SWAP_GB} ГБ"
  fallocate -l "${SWAP_GB}G" /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# ————— 6. общая сеть прокси —————
#
# Внешняя, то есть созданная руками: compose её не создаёт и, главное,
# не удаляет на `down`. Через неё Caddy из стека VEDAL дотягивается до
# контейнеров соседних проектов (c3ag.ru). Подробности — комментарий
# у сервиса proxy в backend/compose.prod.yaml.

say "docker-сеть ${EDGE_NETWORK} и каталог ${EDGE_SITES_DIR}"
if ! docker network inspect "$EDGE_NETWORK" >/dev/null 2>&1; then
  docker network create "$EDGE_NETWORK" >/dev/null
  echo "   создана"
else
  echo "   уже есть"
fi
install -d -m 0755 "$EDGE_SITES_DIR"
if [ ! -f "$EDGE_SITES_DIR/00-readme.caddy" ]; then
  cat > "$EDGE_SITES_DIR/00-readme.caddy" <<'CADDY'
# Сюда кладутся vhost'ы соседних проектов за общим Caddy (контейнер
# vedal-proxy): один файл *.caddy на проект. Образец —
# /opt/vedal-portal/backend/proxy/sites.d/c3ag.ru.caddy.example.
# После правки: docker exec vedal-proxy caddy reload --config /etc/caddy/Caddyfile
CADDY
fi

# ————— 7. репозиторий —————
#
# Клон от имени ubuntu: деплой (scripts/deploy-prod.sh) делает
# `git reset --hard` под этим же пользователем, и каталог, принадлежащий
# root, остановил бы его на первой же выкатке.

say "репозиторий в ${VEDAL_ROOT}"
if [ ! -d "$VEDAL_ROOT/.git" ]; then
  install -d -m 0755 -o "$VEDAL_USER" -g "$VEDAL_USER" "$VEDAL_ROOT"
  sudo -u "$VEDAL_USER" git clone --branch "$VEDAL_BRANCH" "$VEDAL_REPO" "$VEDAL_ROOT"
else
  echo "   уже склонирован: $(sudo -u "$VEDAL_USER" git -C "$VEDAL_ROOT" rev-parse --short HEAD) — обновляю"
  sudo -u "$VEDAL_USER" git -C "$VEDAL_ROOT" fetch -q origin "$VEDAL_BRANCH"
fi
# Каталог состояния деплоя (лок и журнал) — рядом с репозиторием, как
# у deploy-stand-prod.sh; в git он не попадает.
install -d -m 0755 -o "$VEDAL_USER" -g "$VEDAL_USER" "$VEDAL_ROOT/var"

# ————— 8. каталоги копий и состояния —————

say "каталоги: ${BACKUP_DIR}, /var/lib/vedal"
install -d -m 0750 -o "$VEDAL_USER" -g "$VEDAL_USER" "$BACKUP_DIR" "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly"
install -d -m 0755 -o "$VEDAL_USER" -g "$VEDAL_USER" /var/lib/vedal

# ————— 9. systemd: бэкап и сторож —————
#
# enable без --now. Таймер бэкапа на пустой базе снимет дамп меньше
# 10 КБ и честно упадёт; сторож до подъёма стека будет тревожить
# «контейнер не запущен». Включать после §5 runbook'а:
#
#   sudo systemctl start vedal-backup.timer vedal-health.timer
#
# vedal-autodeploy.timer НЕ ставится: на этой площадке выкатка идёт
# из GitHub Actions по SSH (.github/workflows/deploy-cloudru.yml),
# а не опросом main с машины — разбор в runbook'е.

say "systemd-юниты (enabled, не started)"
for unit in vedal-backup.service vedal-backup.timer vedal-health.service vedal-health.timer; do
  install -m 0644 "$VEDAL_ROOT/scripts/$unit" "/etc/systemd/system/$unit"
done
systemctl daemon-reload
systemctl enable vedal-backup.timer vedal-health.timer >/dev/null 2>&1
echo "   $(systemctl is-enabled vedal-backup.timer) vedal-backup.timer, $(systemctl is-enabled vedal-health.timer) vedal-health.timer"

# ————— 10. обновления безопасности —————
#
# Только security-канал и без автоматической перезагрузки: перезапуск
# посреди дня положил бы стек на минуты, а ядро со свежим патчем
# подождёт окна, которое выберет человек.

say "unattended-upgrades"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'CONF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
CONF
cat > /etc/apt/apt.conf.d/52vedal-unattended <<'CONF'
Unattended-Upgrade::Automatic-Reboot "false";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
CONF
systemctl enable --now unattended-upgrades >/dev/null 2>&1 || true

# ————— итог —————

say "готово"
cat <<EOF
   docker:   $(docker --version)
   compose:  $(docker compose version --short 2>/dev/null || echo '?')
   репо:     ${VEDAL_ROOT} @ $(sudo -u "$VEDAL_USER" git -C "$VEDAL_ROOT" rev-parse --short HEAD)
   сеть:     ${EDGE_NETWORK}; vhost'ы соседей: ${EDGE_SITES_DIR}
   копии:    ${BACKUP_DIR}

Дальше — по docs/operations/cloudru_migration_runbook.md:
   1. выйти и войти по SSH заново (группа docker);
   2. собрать ${VEDAL_ROOT}/backend/.env по backend/.env.cloudru.example;
   3. поднять db, восстановить дамп (scripts/restore-db.sh), поднять стек;
   4. после проверки: sudo systemctl start vedal-backup.timer vedal-health.timer
EOF
