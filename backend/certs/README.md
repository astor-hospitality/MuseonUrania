# Сертификаты, которых нет в хранилище JVM

Сюда кладётся корневой сертификат Минцифры — **«Russian Trusted Root CA»**
(и при желании промежуточный «Russian Trusted Sub CA»). Им подписаны двери
GigaChat (`ngw.devices.sberbank.ru`, `gigachat.devices.sberbank.ru`), и без
него соединение из образа `eclipse-temurin` падает с `PKIX path building
failed`. Сертификат публичный, секретом не является и в репозитории лежать
может.

Откуда взять: официальная страница Госуслуг «Установка сертификатов
безопасности» — https://www.gosuslugi.ru/crt (корневой сертификат в формате
`.cer`/`.pem`; файл для Linux называется `russian_trusted_root_ca_pem.crt`).
Скачанный файл переименуйте в `russian_trusted_root_ca.pem` и положите рядом
с этим README. Если файл пришёл в DER (бинарный `.cer`), переведите в PEM:

```bash
openssl x509 -inform der -in russian_trusted_root_ca.cer -out russian_trusted_root_ca.pem
openssl x509 -in russian_trusted_root_ca.pem -noout -subject -enddate   # проверить
```

Dockerfile копирует этот каталог в образ целиком (`/app/certs/`), а портал
подключает файл только при явном пути:

```env
GIGACHAT_CA_BUNDLE=/app/certs/russian_trusted_root_ca.pem
```

Корень **добавляется** к штатным корням JVM, а не заменяет их: Яндекс,
Keycloak, почта и всё остальное продолжают проверяться как раньше. Проверка
TLS не выключается ни при каком значении. Порядок включения GigaChat целиком —
[docs/operations/gigachat_activation.md](../../docs/operations/gigachat_activation.md).

Локальный запуск без Docker: укажите в `GIGACHAT_CA_BUNDLE` абсолютный путь
к файлу на машине.
