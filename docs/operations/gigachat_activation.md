# Включение Ведалины через GigaChat (Сбер)

**Русский** · [English](gigachat_activation.en.md)

## Что уже делает код

Провайдер модели выбирается одной переменной — `VEDAL_LLM_PROVIDER`: `yandex`
(YandexGPT, как было) или `gigachat`. Всё остальное не меняется: материалы
по-прежнему находит портал (`DeterministicSearch`, при включённом RAG —
pgvector), модель получает только подготовленный контекст и связывает его
словами. Нет источников — запрос в модель не уходит, посетитель получает
штатную передачу специалисту. Правила ответа (без цен, диагнозов, сроков)
одни и те же для обоих провайдеров: они живут в `ModelEngine`, а не у модели.

Умолчание — `yandex`. Стенд, где переменная не задана, после обновления
отвечает тем, чем отвечал; переключение — осознанное действие в `.env`.

Отличия провайдера Сбера от Яндекса, которые видны снаружи:

| | YandexGPT | GigaChat |
| --- | --- | --- |
| Доступ | статический Api-Key | ключ авторизации → OAuth-токен на 30 минут (портал обновляет сам) |
| Дверь генерации | `llm.api.cloud.yandex.net/foundationModels/v1/completion` | `gigachat.devices.sberbank.ru/api/v1/chat/completions` |
| Модели | `yandexgpt-lite`, `yandexgpt` | `GigaChat`, `GigaChat-Pro`, `GigaChat-Max` |
| Эмбеддинги | пара `text-search-doc` / `text-search-query`, 256 | одна модель `Embeddings` / `EmbeddingsGigaR`, 1024 |
| TLS | штатные корни JVM | нужен корень Минцифры «Russian Trusted Root CA» |

## Где взять ключ

1. Кабинет разработчика Сбера: <https://developers.sber.ru/studio> → войти
   по Сбер ID → создать проект **GigaChat API** (или открыть существующий).
2. В проекте — «Ключ авторизации» (Authorization key): кабинет показывает его
   один раз, это base64 пары `client_id:client_secret`. Его и кладём
   в `GIGACHAT_AUTH_KEY`. Пара по отдельности (`GIGACHAT_CLIENT_ID`,
   `GIGACHAT_CLIENT_SECRET`) — равноценная альтернатива; при обоих заданных
   побеждает готовый ключ.
3. Область доступа (`GIGACHAT_SCOPE`) — по типу договора: `GIGACHAT_API_PERS`
   (физлицо, пакет бесплатных токенов — для пробы), `GIGACHAT_API_B2B` (ИП и
   юрлицо, предоплата), `GIGACHAT_API_CORP` (юрлицо, постоплата по договору).
   Не та область — `401` на обмене ключа, и это первая вещь, которую стоит
   проверить при отказе.

Ключ не хранить в Jira, GitHub, почте и репозитории. Передавать только
защищённым каналом и класть в `backend/.env` на ВМ или в секреты CI —
ровно как ключ Яндекса.

**Про оплату.** Пакет PERS бесплатный, но с лимитом токенов на год и не для
коммерческого использования; B2B/CORP тарифицируются по токенам, у `GigaChat`
цена за миллион токенов заметно ниже, чем у `Pro` и `Max`. Работа Ведалины
простая — пересказать четыре найденных материала, — поэтому начинать стоит
с `GigaChat`; актуальные тарифы — на странице GigaChat API в кабинете, здесь
они не переписываются, чтобы не устареть.

## Сертификат Минцифры

Двери Сбера подписаны цепочкой «Russian Trusted Root CA». В хранилище JVM
(и в образе `eclipse-temurin`) этого корня нет, и без него соединение падает
с `PKIX path building failed`. Выключать проверку TLS портал не умеет —
намеренно: через эту дверь уходят материалы и ключ.

Порядок:

1. Скачать корневой сертификат с официальной страницы Госуслуг
   (<https://www.gosuslugi.ru/crt>, «сертификат для Linux», PEM).
2. Положить его как `backend/certs/russian_trusted_root_ca.pem` **до сборки
   образа** — Dockerfile копирует каталог в `/app/certs/`. Подробности
   и проверка файла — `backend/certs/README.md`.
3. Указать путь внутри контейнера: `GIGACHAT_CA_BUNDLE=/app/certs/russian_trusted_root_ca.pem`.

Корень добавляется к штатным, а не заменяет их: Яндекс, Keycloak и почта
проверяются как раньше. Нет файла по указанному пути — портал не поднимется
и скажет, где взять.

## Переменные окружения

```env
VEDAL_ASSISTANT_ENGINE=model          # было yandexgpt — принимается по-прежнему
VEDAL_LLM_PROVIDER=gigachat
GIGACHAT_AUTH_KEY=<ключ авторизации проекта GigaChat API>
GIGACHAT_SCOPE=GIGACHAT_API_PERS      # или GIGACHAT_API_B2B / GIGACHAT_API_CORP
GIGACHAT_MODEL=GigaChat               # GigaChat-Pro, GigaChat-Max — без пересборки
GIGACHAT_CA_BUNDLE=/app/certs/russian_trusted_root_ca.pem

# Общие для любого провайдера (старые имена VEDAL_YANDEXGPT_* принимаются):
VEDAL_LLM_TEMPERATURE=0.2
VEDAL_LLM_MAX_TOKENS=600
VEDAL_LLM_FALLBACK=true
```

Ключ Яндекса при провайдере `gigachat` не нужен — и наоборот. Опечатка
в `VEDAL_LLM_PROVIDER` роняет старт с перечнем допустимых значений, а не
откатывает молча к Яндексу.

Переменные доезжают до контейнера через `backend/compose.yaml` (блок
«Ведалина» в сервисе `portal`) — значение в `.env` само по себе внутрь не
попадает.

## Как переключить и как откатить

Переключение — три строки в `backend/.env` (`VEDAL_LLM_PROVIDER=gigachat`,
`GIGACHAT_AUTH_KEY`, `GIGACHAT_CA_BUNDLE`), затем:

```bash
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml --profile app up -d --build
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml logs -f portal | grep -i -E "Ведалина|GigaChat"
```

В журнале старта должно быть «Ведалина отвечает моделью GigaChat …», при
первом вопросе — «Токен GigaChat получен, действует до …».

Откат — `VEDAL_LLM_PROVIDER=yandex` (или убрать переменную: умолчание —
Яндекс) и тот же `up -d`. Ключи Яндекса из `.env` при переключении удалять
не нужно: они не мешают и делают откат одной строкой.

## Smoke-test после включения

```bash
curl -sS https://<домен>/api/assistant/v1/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"какой инкубатор есть для новорождённых?"}'
```

В ответе должны быть `answer` с текстом Ведалины, непустой `sources`
и отсутствие `handoff`, если найден опубликованный источник. Ответ приходит
по частям (`draft`) — так же, как с Яндексом.

Если ответ — перечень ссылок без связного текста, модель не ответила,
и сработал `VEDAL_LLM_FALLBACK=true`: причина в журнале портала
(`GigaChat ответил 401` — ключ или область доступа; `PKIX` — сертификат;
`429` — лимит токенов пакета).

## Поиск по близости (pgvector) и переиндексация

У эмбеддингов свой провайдер — `VEDAL_RAG_PROVIDER`. По умолчанию он
наследует `VEDAL_LLM_PROVIDER`, но его можно задать отдельно, и обычно
**так и нужно сделать**: генерацию перевести на GigaChat сегодня, индекс
оставить на Яндексе.

Причина — размерность. Колонка `knowledge_chunk.embedding` создана миграцией
V34 как `vector(256)` под модели Яндекса; эмбеддинги GigaChat длиннее
(`GIGACHAT_EMBEDDINGS_DIMENSION`, по умолчанию 1024). Портал сверяет модель
с колонкой на старте и при расхождении не поднимается — с текстом, что
делать, — вместо того чтобы падать ошибкой SQL при первой индексации.

Если RAG выключен (`VEDAL_RAG_ENABLED=false`, умолчание), ничего из этого
не касается: индекса нет, и провайдер эмбеддингов не используется.

Перевод индекса на GigaChat — отдельная работа, не часть переключения
генерации:

1. Новая миграция Flyway в `backend/app/src/main/resources/db/migration/`,
   очищающая чанки и меняющая тип колонки (старые векторы в новую колонку
   не влезут, и сравнивать их с новыми всё равно нечем):

   ```sql
   -- V<N>__knowledge_vectors_gigachat.sql
   truncate knowledge_chunk, knowledge_source;
   drop index if exists knowledge_chunk_embedding_idx;
   alter table knowledge_chunk alter column embedding type vector(1024);
   create index knowledge_chunk_embedding_idx
       on knowledge_chunk using hnsw (embedding vector_cosine_ops);
   ```

   `truncate` здесь выполняется владельцем схемы при накате, а не рантаймом:
   у роли приложения право `TRUNCATE` отозвано (V34), и это не мешает.
2. `VEDAL_RAG_PROVIDER=gigachat`, `GIGACHAT_EMBEDDINGS_MODEL=Embeddings`
   (или `EmbeddingsGigaR`), `GIGACHAT_EMBEDDINGS_DIMENSION=1024`.
3. После старта — полная переиндексация из админки (раздел «Индекс
   Ведалины», кнопка переиндексации): чанки считаются заново моделью Сбера
   и помечаются в колонке `model` как `gigachat/Embeddings`.
4. Порог `VEDAL_RAG_MAX_DISTANCE` калибруется заново — расстояния у другой
   модели другие (порядок замера — в [конвейере Ведалины](vedalina_rag_pipeline.md)).

Откат индекса — обратная миграция (`vector(256)`) и переиндексация Яндексом.

## Ограничения

- Размерность 1024 для моделей Сбера взята из документации GigaChat API
  и в коде не прошита — задаётся настройкой и сверяется с каждым ответом.
  Если модель вернёт вектор другой длины, портал откажет с текстом, а не
  запишет его.
- Корпус документов по-прежнему тот же, что и с Яндексом (issue #38):
  смена модели не добавляет материалов, она меняет только формулировку.
- Голосовой ввод (SpeechKit) остаётся на Яндексе — он не часть модели
  и этим переключателем не затрагивается.
