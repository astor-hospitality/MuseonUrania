# MuseonUrania / VEDAL Portal

**Русский** · [English](README.en.md)

Портфолио-кейс: сайт, админка с CRM, API и AI-ассистент «Ведалина» для
производителя медицинского оборудования (неонатология, реанимация,
анестезиология). Один репозиторий, одна платформа: публичная витрина, приём
заявок и закрытый контур сотрудников за Keycloak. Публичная версия — без
доступов, договоров, коммерческих условий и ключей облаков: они передаются
заказчику отдельным пакетом вне git.

**Живой сайт:** <https://vedal-med.ru>

## Состояние на 12 октября 2026

Что работает:

- публичный сайт и каталог изделий, формы запроса КП и сервисных обращений,
  новости, документы (публичная выдача документов закрыта выключателем);
- админка: содержимое сайта и CRM — заявки, клиенты, сделки, КП, сотрудники,
  журнал аудита; вход сотрудников через Keycloak с ролями и вторым фактором;
- Ведалина: отвечает только по опубликованным материалам, с голосовым вводом
  и озвучиванием ответа; провайдеры модели, эмбеддингов и речи переключаются
  настройкой;
- ночные резервные копии базы с проверкой восстановлением, проверка живости
  раз в пять минут, выкатка из GitHub Actions с подтверждением рецензента.

Что изменилось в октябре (PR #135, 8 октября; хвост — закрытие устаревших PR
11 октября):

| Было | Стало |
| --- | --- |
| ВМ в Yandex Cloud, выкатка таймером на машине | ВМ в Cloud.ru, выкатка по SSH из GitHub Actions (environment `production`) |
| Ведалина только через YandexGPT | три провайдера модели: YandexGPT, GigaChat (Сбер), Cloud.ru Foundation Models — `VEDAL_LLM_PROVIDER` |
| эмбеддинги RAG только Яндекса | провайдер эмбеддингов переключается отдельно — `VEDAL_RAG_PROVIDER` (`yandex` / `gigachat` / `cloudru`) |
| распознавание и синтез речи только Yandex SpeechKit | распознавание — SpeechKit или Whisper в Cloud.ru (`VEDAL_STT_PROVIDER`); озвучивание — SpeechKit или SaluteSpeech (`VEDAL_TTS_PROVIDER`) |
| один прокси на один проект | общий Caddy на ВМ: соседние проекты подключаются файлами `sites.d/` через docker-сеть `edge` |
| бэкап выгружался образом `minio/mc` | выгрузка в бакет через `aws-cli` |

Там же закрыты уязвимости зависимостей (Next.js 16.3.6, BouncyCastle 1.86).
Умолчания не менялись: без переменных стек отвечает так же, как до октября;
выбор провайдера — осознанное действие в `.env`. Открытые пункты после
переезда: учётные записи сотрудников в Keycloak заводятся заново (бэкапа
не было), при смене провайдера эмбеддингов нужна переиндексация базы знаний.

Последний релиз — [v1.0.0](https://github.com/astor-hospitality/MuseonUrania/releases/tag/v1.0.0)
(18 сентября 2026); переезд выкачен из `main` без нового тега.

## Архитектура

| Слой | Из чего сделано |
| --- | --- |
| Frontend (`frontend/`) | Next.js 16, React 19, TypeScript, CSS Modules; сайт и админка в одном приложении (`app/(site)`, `app/(admin)`); Vitest + Testing Library |
| Backend (`backend/`) | Java 25, Spring Boot 4.1, Maven-реактор из модулей `common`, `iam`, `audit`, `catalog`, `content`, `documents`, `crm`, `gateway`, `notifications`, `assistant`, `chat`, `admin`, `app`; Spring Data JPA, Flyway, транзакционный outbox |
| Шлюз (`backend/api-gateway/`) | отдельное приложение на Spring Cloud Gateway: сайт и API на одном источнике, лимит тела, проверка токена |
| База | PostgreSQL 16 с pgvector (векторы базы знаний Ведалины); схема только миграциями Flyway |
| События | Kafka 3.9 (KRaft) + Debezium Connect: outbox → топики |
| Файлы | S3-совместимое хранилище через AWS SDK: открытый на чтение бакет медиа и закрытый бакет документов (выдача только через контроллер с аудитом) |
| Auth | Keycloak 26, OIDC + PKCE, роли `portal-admin` / `portal-sales` / `portal-production`, политика второго фактора |
| Ассистент | модуль `assistant`: детерминированный поиск по опубликованному → при `VEDAL_ASSISTANT_ENGINE=model` модель формулирует ответ поверх найденного; RAG по pgvector включается `VEDAL_RAG_ENABLED`; ограничения (нет диагнозов, цен, сроков, статусов регистрации) живут в `Guardrails` до вызова модели |
| Инфраструктура | Docker Compose, Caddy (TLS от Let's Encrypt), systemd-таймеры бэкапа и проверки живости, GitHub Actions (CI, CodeQL, Trivy, выкатка) |

```text
посетитель ──HTTPS──▶ Caddy (proxy) ──▶ api-gateway ──┬──▶ site (Next.js)
                        │                             └──▶ portal (Spring Boot)
                        │                                    ├─ PostgreSQL 16 + pgvector
                        │                                    ├─ Kafka ◀─ Debezium (outbox)
                        │                                    ├─ S3: media / documents
                        │                                    ├─ Keycloak (проверка токена)
                        │                                    └─ Ведалина ──▶ LLM / эмбеддинги / STT / TTS
                        │                                                   (yandex | gigachat | cloudru | salute)
                        └──▶ Keycloak (вход сотрудников, отдельный поддомен)
```

Три двери и больше никаких: `Public API` `/api/public/v1/*` (только
опубликованное, read-only), `Forms API` `/api/forms/v1/leads` (единственная
запись снаружи), `Admin API` `/api/admin/v1/**` (токен Keycloak). Ассистент —
`/api/assistant/v1/*`. Сайт читает каталог на сборке, поэтому падение бэкенда
не роняет уже собранные страницы: живой бэкенд нужен формам и Ведалине.

## Локальный запуск

Нужен только Docker с Compose v2. Первый запуск собирает образы (около
десяти минут), дальше — секунды.

```bash
./scripts/up.sh          # Windows: .\scripts\up.ps1
```

Скрипт проверит Docker, создаст `backend/.env` со случайными паролями,
выполнит `docker compose -f backend/compose.yaml --profile app up -d --build`
и дождётся готовности сайта.

| Адрес | Назначение |
| --- | --- |
| `http://localhost:8080` | сайт и API через шлюз |
| `http://localhost:8080/admin/` | админка |
| `http://localhost:8080/swagger-ui.html` | Swagger UI |
| `http://localhost:8180` | Keycloak локального стека |

Остановить без удаления данных / пересоздать данные с нуля:

```bash
docker compose -f backend/compose.yaml --profile app down
docker compose -f backend/compose.yaml --profile app down -v
```

Разработка с хоста: инфраструктура в контейнерах, приложения — рядом.

```bash
docker compose -f backend/compose.yaml up -d          # db, kafka, keycloak, connect
cd backend && ./mvnw spring-boot:run -pl app
cd frontend && npm install && npm run dev             # без NEXT_PUBLIC_API_URL — на локальном контенте
```

Проверки (те же, что гоняет CI):

```bash
cd backend && ./mvnw --batch-mode verify              # тесты и порог покрытия
cd backend/api-gateway && ./mvnw --batch-mode verify
cd frontend && npx tsc --noEmit && npm run lint && npm test && npm run build
```

## Конфигурация

В git только шаблоны; боевые `.env` не коммитятся:

- `backend/.env.example` — локальный стек в Docker;
- `backend/.env.host.example` — backend с хоста против контейнеров;
- `backend/.env.cloudru.example` — прод-контур на ВМ, каждая переменная
  с комментарием, откуда её брать;
- `frontend/.env.example` — `NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_MEDIA_URL`,
  `NEXT_PUBLIC_OIDC_ISSUER`, `NEXT_PUBLIC_OIDC_CLIENT_ID`,
  `NEXT_PUBLIC_DOCUMENTS_ENABLED`, `VEDAL_API_INTERNAL_URL`.

Ключевые группы переменных портала (имена, без значений):

| Группа | Переменные |
| --- | --- |
| База и Keycloak | `VEDAL_DB_*`, `VEDAL_RUNTIME_*`, `VEDAL_KEYCLOAK_*`, `VEDAL_OIDC_SVC_CLIENT_SECRET` |
| Домены и источники | `VEDAL_DOMAIN`, `VEDAL_PUBLIC_URL`, `VEDAL_SITE_URL`, `VEDAL_ALLOWED_ORIGINS`, `VEDAL_ADMIN_ORIGINS`, `VEDAL_ACME_EMAIL` |
| Хранилище | `VEDAL_S3_ENDPOINT`, `VEDAL_S3_REGION`, `VEDAL_S3_ACCESS_KEY`, `VEDAL_S3_SECRET_KEY`, `VEDAL_S3_BUCKET_MEDIA`, `VEDAL_S3_BUCKET_DOCUMENTS`, `VEDAL_MEDIA_URL` |
| Почта | `SPRING_MAIL_HOST`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `VEDAL_MAIL_FROM`, `VEDAL_NOTIFICATIONS_MANAGER` |
| Ведалина: режим | `VEDAL_ASSISTANT_ENGINE` (`deterministic` / `model`), `VEDAL_LLM_PROVIDER`, `VEDAL_LLM_TEMPERATURE`, `VEDAL_LLM_MAX_TOKENS`, `VEDAL_LLM_FALLBACK` |
| Ведалина: провайдеры | `VEDAL_YANDEXGPT_API_KEY`, `VEDAL_YANDEXGPT_MODEL_URI`; `GIGACHAT_AUTH_KEY`, `GIGACHAT_MODEL`, `GIGACHAT_CA_BUNDLE`; `CLOUDRU_API_KEY`, `CLOUDRU_MODEL` |
| Ведалина: RAG | `VEDAL_RAG_ENABLED`, `VEDAL_RAG_PROVIDER`, `VEDAL_RAG_MAX_DISTANCE`, `VEDAL_RAG_*_MODEL_URI`, `GIGACHAT_EMBEDDINGS_*`, `CLOUDRU_EMBEDDINGS_*` |
| Ведалина: речь | `VEDAL_STT_PROVIDER`, `CLOUDRU_STT_MODEL`, `VEDAL_SPEECHKIT_API_KEY`; `VEDAL_TTS_PROVIDER`, `SALUTE_AUTH_KEY`, `SALUTE_TTS_VOICE`, `SALUTE_TTS_FORMAT` |
| Выключатели и эксплуатация | `VEDAL_PUBLIC_DOCUMENTS_ENABLED`, `VEDAL_PRIVACY_RETENTION*`, `VEDAL_BACKUP_*`, `VEDAL_HEALTH_*`, `VEDAL_ALERT_WEBHOOK` |

Опечатка в имени провайдера роняет старт с перечнем допустимых значений,
а не откатывает молча к умолчанию.

## Как развёрнуто

Один compose-проект на одной ВМ: `backend/compose.yaml` (базовый стек) +
`backend/compose.prod.yaml` (прокси, бэкап, сторож, S3-режим хранилища) +
`backend/compose.cloudru.yaml` (наложение под особенности площадки).
Единственный контейнер с портами наружу — Caddy: он получает и продлевает
сертификаты сам, держит CSP/HSTS и закрывает `/admin` снаружи. Соседние
проекты на той же машине входят во внешнюю docker-сеть `edge` и описываются
файлами в `sites.d/` — без своего прокси и без публикации портов. Шлюз
слушает только петлю: по нему ходят сторож и выкатка.

Выкатка — `.github/workflows/deploy-cloudru.yml`: пуш в `main` ждёт
подтверждения в environment `production`, затем раннер по SSH запускает
`scripts/deploy-prod.sh`. Откат — `git revert` и та же выкатка. Первичная
загрузка ВМ — `scripts/bootstrap-cloudru.sh`, восстановление базы —
`scripts/restore-db.sh`. Ночью `vedal-backup.timer` снимает дамп, проверяет
его восстановлением и выгружает в бакет; `vedal-health.timer` раз в пять
минут проверяет портал, контейнеры, диск и очередь писем и шлёт тревогу
на переходе состояния. Адреса, ключи и имена хостов живут в `.env` на
машине и в секретах GitHub, не в репозитории.

## Карта документации

| Куда | Что там |
| --- | --- |
| [docs/PROJECT.md](docs/PROJECT.md) | точка входа: что за проект, карта репозитория, три двери, порядок работ |
| [docs/documentation_rules.md](docs/documentation_rules.md) | каждый `.md` в двух версиях (`ru` ведущая, `en` зеркало), словарь терминов |
| [docs/architecture/](docs/architecture/) | бриф собственника, целевая и инфраструктурная архитектура |
| [docs/operations/cloudru_migration_runbook.md](docs/operations/cloudru_migration_runbook.md) | переезд на Cloud.ru: первая загрузка ВМ, база, DNS, Keycloak, выкатка, общий прокси |
| [docs/operations/production_move_runbook.md](docs/operations/production_move_runbook.md) | таблицы переменных, подводные камни базы, таблица приёмки |
| [docs/operations/backups.md](docs/operations/backups.md), [monitoring.md](docs/operations/monitoring.md), [mfa_rollout.md](docs/operations/mfa_rollout.md) | резервные копии, проверка живости, второй фактор |
| [docs/operations/yandexgpt_activation.md](docs/operations/yandexgpt_activation.md), [gigachat_activation.md](docs/operations/gigachat_activation.md), [cloudru_models_activation.md](docs/operations/cloudru_models_activation.md), [salutespeech_activation.md](docs/operations/salutespeech_activation.md) | включение, переключение и откат провайдеров Ведалины |
| [docs/operations/vedalina_rag_pipeline.md](docs/operations/vedalina_rag_pipeline.md) | как формируется база знаний, pgvector, переиндексация |
| [docs/operations/public_feature_switches.md](docs/operations/public_feature_switches.md) | обратимые выключатели публичного сайта |
| [docs/api/](docs/api/) | OpenAPI публичного и админского API, коллекция Postman |
| [backend/README.md](backend/README.md), [frontend/README.md](frontend/README.md) | устройство серверной части и сайта; у каждого модуля бэкенда свой README |
| [docs/frontend/](docs/frontend/), [docs/strategy/](docs/strategy/), [docs/legal/](docs/legal/), [docs/products/](docs/products/) | карта сайта и брифы страниц, бизнес-рамка и спецификация Ведалины, требования соответствия, подтверждённые данные по изделиям |
| [HANDOFF.md](HANDOFF.md) | пакет передачи фронтенда и жёсткие правила контента |

## Сопровождение

- Ведущий разработчик: архитектура, backend и CMS, инфраструктура и выкатка,
  AI-контур, приёмка интеграции.
- Frontend-разработчик: UX/UI и реализация сайта и админки на Next.js.
- Сторона заказчика: утверждение структуры, деловых формулировок и фактов
  об изделиях; медицинские заявления, цены и статусы регистрации не
  выдумываются — недостающее помечается `ожидает уточнения`.

## Правила публичного репозитория

В git не попадают `.env`, ключи, токены, дампы, сертификаты, договоры, акты,
счета, реквизиты, персональные контакты, клиентские презентации и исходные
медиа, а также инструкции с адресами закрытых сервисов. Проверка —
[docs/security/public_repo_cleanup_2026-09-08.md](docs/security/public_repo_cleanup_2026-09-08.md).
