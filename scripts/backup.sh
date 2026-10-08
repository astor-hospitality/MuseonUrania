#!/usr/bin/env bash
# Резервная копия базы VEDAL Portal.
#
# Что копируется и почему только это. База — единственное место, где данные
# рождаются: заявки, клиенты, сделки, КП, разговоры и журнал аудита есть
# только здесь. Файлы документов лежат в объектном хранилище, у которого
# своя избыточность, а образы и конфигурация собираются из git заново.
#
# Формат — custom (-Fc): он сжат и умеет частичное восстановление,
# в отличие от простого SQL-дампа, который льётся только целиком.
#
# Проверка восстановлением включена в тот же прогон. Копия, которую никто
# не разворачивал, — это надежда, а не резерв: битый дамп выглядит как файл
# нужного размера ровно до того дня, когда он понадобится.

set -euo pipefail

DB_CONTAINER="${VEDAL_DB_CONTAINER:-vedal-db}"
DB_NAME="${VEDAL_DB_NAME:-vedal}"
DB_USER="${VEDAL_DB_USER:-vedal}"
DEST="${VEDAL_BACKUP_DIR:-/var/backups/vedal}"

# Сколько держим. Ежедневные — на случай «испортили вчера, заметили сегодня»,
# недельные — на случай «испортили три недели назад, заметили сейчас».
KEEP_DAILY="${VEDAL_BACKUP_KEEP_DAILY:-7}"
KEEP_WEEKLY="${VEDAL_BACKUP_KEEP_WEEKLY:-4}"

now=$(date +%Y%m%d-%H%M%S)
daily="$DEST/daily"
weekly="$DEST/weekly"
dump="$daily/vedal-$now.dump"

mkdir -p "$daily" "$weekly"

echo "== снимаем дамп $DB_NAME из $DB_CONTAINER"
# Дамп идёт через stdout контейнера в файл на хосте: писать внутрь
# контейнера значит держать копию там же, где и оригинал, — то есть
# терять её вместе с ним.
docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$dump"

size=$(stat -c %s "$dump")
if [ "$size" -lt 10000 ]; then
    echo "ОШИБКА: дамп меньше 10 КБ ($size байт) — это не копия базы"
    rm -f "$dump"
    exit 1
fi
echo "   получилось $((size / 1024)) КБ"

# ————— проверка восстановлением —————
#
# Разворачиваем во временную базу рядом. Не в ту же самую и не «на всякий
# случай с --clean»: восстановление поверх живой базы ради проверки — это
# ровно тот сценарий, от которого бэкапы и защищают.
check="vedal_restore_check_$$"
echo "== проверяем восстановлением в $check"
cleanup() {
    docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d postgres \
        -c "DROP DATABASE IF EXISTS $check" > /dev/null 2>&1 || true
}
trap cleanup EXIT

docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d postgres \
    -c "CREATE DATABASE $check" > /dev/null

# pg_restore не умеет читать с stdin хоста через docker exec без -i,
# поэтому дамп подаётся в контейнер потоком.
docker exec -i "$DB_CONTAINER" pg_restore -U "$DB_USER" -d "$check" --no-owner < "$dump" > /dev/null 2>&1 || true

tables=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$check" -tAc \
    "select count(*) from information_schema.tables where table_schema='public'")
leads=$(docker exec "$DB_CONTAINER" psql -U "$DB_USER" -d "$check" -tAc \
    "select count(*) from lead" 2>/dev/null || echo 0)

# Двадцать таблиц — нижняя граница здравого смысла: в схеме их за двадцать,
# и меньше означает, что восстановилась половина. Пустая база проходит
# проверку по заявкам (их может не быть), но не по таблицам.
if [ "$tables" -lt 20 ]; then
    echo "ОШИБКА: в восстановленной базе только $tables таблиц — дамп неполный"
    exit 1
fi
echo "   восстановлено: таблиц $tables, заявок $leads"

# ————— ротация —————
#
# Копия понедельника переезжает в недельные. День недели, а не «каждый
# седьмой файл»: так недельные копии ложатся на предсказуемые даты, и по
# имени файла видно, за какое число копия.
if [ "$(date +%u)" = "1" ]; then
    cp "$dump" "$weekly/"
    echo "== понедельник: копия отложена в недельные"
fi

trim() {
    local dir="$1" keep="$2"
    local count
    count=$(find "$dir" -maxdepth 1 -name 'vedal-*.dump' | wc -l)
    if [ "$count" -gt "$keep" ]; then
        find "$dir" -maxdepth 1 -name 'vedal-*.dump' -printf '%T@ %p\n' \
            | sort -n | head -n "$((count - keep))" | cut -d' ' -f2- \
            | while read -r old; do
                echo "   удаляем старое: $(basename "$old")"
                rm -f "$old"
            done
    fi
}

trim "$daily" "$KEEP_DAILY"
trim "$weekly" "$KEEP_WEEKLY"

# ————— копия вне машины —————
#
# Резерв, лежащий на той же машине, спасает от испорченных данных и не
# спасает от потери машины. Выгрузка включается ключом с правом записи
# в бакет; без него скрипт не падает, а говорит, чего не хватает:
# локальные копии всё равно ценнее их отсутствия.
if [ -n "${VEDAL_BACKUP_S3_BUCKET:-}" ] && [ -n "${VEDAL_S3_ACCESS_KEY:-}" ]; then
    echo "== выгружаем в ${VEDAL_BACKUP_S3_BUCKET}"
    # aws-cli, а не mc: образы MinIO в 2025 исчезли из Docker Hub, а с quay.io тянутся не те теги.
    docker run --rm \
        -e AWS_ACCESS_KEY_ID="$VEDAL_S3_ACCESS_KEY" -e AWS_SECRET_ACCESS_KEY="${VEDAL_S3_SECRET_KEY:-}" \
        -e AWS_DEFAULT_REGION="${VEDAL_S3_REGION:-ru-central1}" -e AWS_EC2_METADATA_DISABLED=true \
        -v "$daily:/dumps:ro" \
        amazon/aws-cli:2.22.35 --endpoint-url "${VEDAL_S3_ENDPOINT:-https://storage.yandexcloud.net}" \
        s3 cp --quiet "/dumps/$(basename "$dump")" "s3://${VEDAL_BACKUP_S3_BUCKET}/db/$(basename "$dump")" \
        && echo "   выгружено" || echo "   ВНИМАНИЕ: выгрузка не удалась, копия осталась только на машине"
else
    echo "== выгрузка вне машины не настроена (VEDAL_BACKUP_S3_BUCKET, ключ с правом записи)"
    echo "   копии лежат только на этой машине: потеря ВМ — потеря копий"
fi

echo "== готово: $dump"
