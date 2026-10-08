# Включение Ведалины через Cloud.ru Foundation Models

**Русский** · [English](cloudru_models_activation.en.md)

## Что уже делает код

Провайдер модели выбирается одной переменной — `VEDAL_LLM_PROVIDER`: `yandex`
(YandexGPT, как было), `gigachat` (Сбер напрямую) или `cloudru` — Cloud.ru
Evolution Foundation Models. Всё остальное не меняется: материалы по-прежнему
находит портал (`DeterministicSearch`, при включённом RAG — pgvector), модель
получает только подготовленный контекст и связывает его словами. Нет
источников — запрос в модель не уходит, посетитель получает штатную передачу
специалисту. Правила ответа (без цен, диагнозов, сроков) одни и те же для всех
провайдеров: они живут в `ModelEngine`, а не у модели.

Умолчание — `yandex`. Стенд, где переменная не задана, после обновления
отвечает тем, чем отвечал; переключение — осознанное действие в `.env`.

Зачем третий провайдер, если GigaChat уже есть: Cloud.ru отдаёт те же модели
Сбера (и открытые модели) через OpenAI-совместимую дверь со статическим
ключом — без OAuth-обмена, области доступа и корня Минцифры. Это проще
включить и проще отлаживать; тарифицируется через договор с Cloud.ru.

Отличия, которые видны снаружи:

| | YandexGPT | GigaChat (Сбер) | Cloud.ru Foundation Models |
| --- | --- | --- | --- |
| Доступ | статический Api-Key | ключ авторизации → OAuth-токен на 30 минут | статический API-ключ, `Authorization: Bearer` |
| Дверь генерации | `llm.api.cloud.yandex.net/foundationModels/v1/completion` | `gigachat.devices.sberbank.ru/api/v1/chat/completions` | `foundation-models.api.cloud.ru/v1/chat/completions` |
| Модели | `yandexgpt-lite`, `yandexgpt` | `GigaChat`, `GigaChat-Pro`, `GigaChat-Max` | `GigaChat/GigaChat-2-Max`, `GigaChat/GigaChat-3-Pro` и другие из `/v1/models` |
| Эмбеддинги | пара `text-search-doc` / `text-search-query`, 256 | `Embeddings` / `EmbeddingsGigaR`, 1024 | `ai-sage/Giga-Embeddings-instruct-480M` / `-3B`, размерность узнаётся по первому ответу |
| TLS | штатные корни JVM | нужен корень Минцифры | штатные корни JVM |

Тело запроса и разбор потока у Сбера и Cloud.ru общие (`OpenAiChat`):
схема OpenAI, поток SSE из строк `data: {...}` с приращениями и `data: [DONE]`
в конце.

## Где взять ключ

1. Консоль Cloud.ru: <https://console.cloud.ru> → войти → сервис
   **Evolution Foundation Models**.
2. В сервисе — раздел **API-ключи** → создать ключ. Консоль показывает его
   один раз; его и кладём в `CLOUDRU_API_KEY`. Ключ один на генерацию
   и эмбеддинги.
3. Доступные модели и их идентификаторы — в каталоге сервиса; тот же список
   отдаёт дверь `GET /v1/models` с этим ключом:

   ```bash
   curl -sS https://foundation-models.api.cloud.ru/v1/models \
     -H "Authorization: Bearer $CLOUDRU_API_KEY" | jq -r '.data[].id'
   ```

Ключ не хранить в Jira, GitHub, почте и репозитории. Передавать только
защищённым каналом и класть в `backend/.env` на ВМ или в секреты CI —
ровно как ключи Яндекса и Сбера.

**Про оплату.** Foundation Models тарифицируются по токенам по договору
с Cloud.ru; у `GigaChat-2-Max` и `GigaChat-3-Pro` цены разные, у эмбеддингов —
свои. Работа Ведалины простая — пересказать четыре найденных материала, —
поэтому при необходимости сэкономить стоит посмотреть в каталоге более
дешёвую модель того же семейства. Актуальные тарифы — на странице сервиса
в консоли, здесь они не переписываются, чтобы не устареть.

## Переменные окружения

```env
VEDAL_ASSISTANT_ENGINE=model          # было yandexgpt — принимается по-прежнему
VEDAL_LLM_PROVIDER=cloudru
CLOUDRU_API_KEY=<API-ключ из консоли Cloud.ru>
CLOUDRU_MODEL=GigaChat/GigaChat-2-Max # GigaChat/GigaChat-3-Pro и другие — без пересборки
# CLOUDRU_BASE_URL=https://foundation-models.api.cloud.ru/v1   # умолчание, менять незачем

# Общие для любого провайдера (старые имена VEDAL_YANDEXGPT_* принимаются):
VEDAL_LLM_TEMPERATURE=0.2
VEDAL_LLM_MAX_TOKENS=600
VEDAL_LLM_FALLBACK=true
```

Ключи Яндекса и Сбера при провайдере `cloudru` не нужны — и наоборот.
Опечатка в `VEDAL_LLM_PROVIDER` роняет старт с перечнем допустимых значений,
а не откатывает молча к Яндексу. Пустой или испорченный `CLOUDRU_API_KEY`
(пробелы, кириллица от копирования) — тоже отказ на старте с текстом, где
взять ключ.

Переменные доезжают до контейнера через `backend/compose.yaml` (блок
«Ведалина» в сервисе `portal`) — значение в `.env` само по себе внутрь не
попадает.

## Как переключить и как откатить

Переключение — две строки в `backend/.env` (`VEDAL_LLM_PROVIDER=cloudru`,
`CLOUDRU_API_KEY`), затем:

```bash
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml --profile app up -d --build
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml logs -f portal | grep -i -E "Ведалина|Cloud.ru"
```

В журнале старта должно быть «Ведалина отвечает моделью Cloud.ru
GigaChat/GigaChat-2-Max». Обмена ключа на токен нет, поэтому строки
про токен, как у Сбера, не будет.

Откат — `VEDAL_LLM_PROVIDER=yandex` (или `gigachat`, или убрать переменную:
умолчание — Яндекс) и тот же `up -d`. Ключи других провайдеров из `.env` при
переключении удалять не нужно: они не мешают и делают откат одной строкой.

## Smoke-test после включения

```bash
curl -sS https://<домен>/api/assistant/v1/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"какой инкубатор есть для новорождённых?"}'
```

В ответе должны быть `answer` с текстом Ведалины, непустой `sources`
и отсутствие `handoff`, если найден опубликованный источник. Ответ приходит
по частям (`draft`) — так же, как с Яндексом и Сбером.

Если ответ — перечень ссылок без связного текста, модель не ответила,
и сработал `VEDAL_LLM_FALLBACK=true`: причина в журнале портала
(`Cloud.ru ответил 401` — ключ; `404` — нет такой модели, проверить
`CLOUDRU_MODEL` по `/v1/models`; `429` — лимит запросов тарифа).

## Поиск по близости (pgvector) и переиндексация

У эмбеддингов свой провайдер — `VEDAL_RAG_PROVIDER`. По умолчанию он
наследует `VEDAL_LLM_PROVIDER`, но его можно задать отдельно, и обычно
**так и нужно сделать**: генерацию перевести на Cloud.ru сегодня, индекс
оставить на Яндексе.

Причина — размерность. Колонка `knowledge_chunk.embedding` создана миграцией
V34 как `vector(256)` под модели Яндекса. Длина вектора у
`Giga-Embeddings-instruct` документацией Cloud.ru не названа, поэтому портал
её не постулирует: при пустой `CLOUDRU_EMBEDDINGS_DIMENSION` он делает
пробный запрос на старте, узнаёт длину по первому ответу и с ней сверяет
колонку — при расхождении не поднимается, с текстом, что делать. Заданное
число сверяется с каждым ответом так же, как у Сбера.

Если RAG выключен (`VEDAL_RAG_ENABLED=false`, умолчание), ничего из этого
не касается: индекса нет, и провайдер эмбеддингов не используется.

Перевод индекса на Cloud.ru — отдельная работа, не часть переключения
генерации:

1. Узнать размерность модели — одним запросом с тем же ключом:

   ```bash
   curl -sS https://foundation-models.api.cloud.ru/v1/embeddings \
     -H "Authorization: Bearer $CLOUDRU_API_KEY" -H 'Content-Type: application/json' \
     -d '{"model":"ai-sage/Giga-Embeddings-instruct-480M","input":["размерность"]}' \
     | jq '.data[0].embedding | length'
   ```

2. Новая миграция Flyway в `backend/app/src/main/resources/db/migration/`,
   очищающая чанки и меняющая тип колонки на полученную длину (старые векторы
   в новую колонку не влезут, и сравнивать их с новыми всё равно нечем):

   ```sql
   -- V<N>__knowledge_vectors_cloudru.sql
   truncate knowledge_chunk, knowledge_source;
   drop index if exists knowledge_chunk_embedding_idx;
   alter table knowledge_chunk alter column embedding type vector(<размерность>);
   create index knowledge_chunk_embedding_idx
       on knowledge_chunk using hnsw (embedding vector_cosine_ops);
   ```

   `truncate` здесь выполняется владельцем схемы при накате, а не рантаймом:
   у роли приложения право `TRUNCATE` отозвано (V34), и это не мешает.
   Индекс HNSW в pgvector ограничен 2000 измерениями: если модель даёт
   больше, индекс не создать, и выбирать придётся другую модель эмбеддингов.
3. `VEDAL_RAG_PROVIDER=cloudru`, `CLOUDRU_EMBEDDINGS_MODEL=ai-sage/Giga-Embeddings-instruct-480M`
   (или `-3B`), `CLOUDRU_EMBEDDINGS_DIMENSION=<размерность>` — явное число
   лучше пустого: так старт не тратит пробный запрос и обещание зафиксировано
   в `.env` рядом с миграцией.
4. После старта — полная переиндексация из админки (раздел «Индекс
   Ведалины», кнопка переиндексации): чанки считаются заново моделью Cloud.ru
   и помечаются в колонке `model` как `cloudru/ai-sage/Giga-Embeddings-instruct-480M`.
5. Порог `VEDAL_RAG_MAX_DISTANCE` калибруется заново — расстояния у другой
   модели другие (порядок замера — в [конвейере Ведалины](vedalina_rag_pipeline.md)).

Откат индекса — обратная миграция (`vector(256)`) и переиндексация Яндексом.

## Распознавание речи (голосовой ввод) через Whisper

Голосовой ввод Ведалины — две половины: распознавание (речь посетителя →
текст) и синтез (ответ → звук). Распознавание переключается на Cloud.ru
той же дверью и тем же ключом, что модель: `POST /v1/audio/transcriptions`
в схеме OpenAI, модель `openai/whisper-large-v3`. Синтез остаётся
на SpeechKit Яндекса при любой настройке — у Cloud.ru Foundation Models
модели синтеза сегодня нет, — поэтому `VEDAL_SPEECHKIT_API_KEY` нужен
и после переключения: без него посетитель диктует, но ответа не слышит,
и `GET /api/assistant/v1/voice` отвечает `available: false`.

Переключатель свой, отдельный от `VEDAL_LLM_PROVIDER`, — голос и модель
переезжают в разное время:

```env
VEDAL_STT_PROVIDER=cloudru                 # yandex — SpeechKit; умолчание — yandex
CLOUDRU_API_KEY=<тот же ключ, что у модели>
CLOUDRU_STT_MODEL=openai/whisper-large-v3  # умолчание; другая модель — без пересборки
# VEDAL_STT_LANGUAGE=ru                    # подсказка языка Whisper; пусто — определяет сам
# VEDAL_STT_TIMEOUT=PT30S                  # ожидание ответа, как у SpeechKit
VEDAL_SPEECHKIT_API_KEY=<ключ SpeechKit>   # по-прежнему нужен для синтеза
```

Правила те же, что у модели: `cloudru` без `CLOUDRU_API_KEY` или с пустой
`CLOUDRU_STT_MODEL` роняет старт с текстом, называющим переменные; опечатка
в `VEDAL_STT_PROVIDER` — отказ с перечнем допустимых значений, а не тихий
откат к Яндексу. При `yandex` ключ Cloud.ru не нужен, а пустой
`VEDAL_SPEECHKIT_API_KEY`, как и раньше, не роняет старт — голос просто
недоступен.

Фронт присылает те же 30 секунд PCM16 16 кГц, что и для SpeechKit; портал
заворачивает их в WAV и отправляет файлом (`file`) вместе с `model`,
`language` и `response_format=json`. Предел двери — 25 МБ на файл,
30 секунд речи — меньше мегабайта. Как и у SpeechKit, ни звук, ни текст,
ни тела ошибок провайдера в журнал и хранилище не попадают.

Переключение — `VEDAL_STT_PROVIDER=cloudru` в `backend/.env` и тот же
`up -d`; в журнале старта — «Ведалина распознаёт речь моделью Cloud.ru
openai/whisper-large-v3». Проверка — продиктовать вопрос в чате Ведалины
на сайте или:

```bash
# 2 секунды тишины в формате фронта: ответ 200 с пустым или почти пустым text
head -c 64000 /dev/zero | curl -sS https://<домен>/api/assistant/v1/voice/recognize \
  -H 'Content-Type: application/octet-stream' -H 'X-Voice-Consent: true' --data-binary @-
```

Ответ `503 Голос временно недоступен` — ключа нет; `502` — дверь Cloud.ru
не ответила 200 (ключ, имя модели по `/v1/models`, лимит тарифа) — причина
в ответ намеренно не попадает, проверять по консоли Cloud.ru.

Откат — `VEDAL_STT_PROVIDER=yandex` (или убрать переменную) и тот же
`up -d`; ключ Cloud.ru из `.env` удалять не нужно. Откат независим от
`VEDAL_LLM_PROVIDER`: модель может остаться на Cloud.ru, а голос вернуться
на SpeechKit, и наоборот.

## Ограничения

- Размерность эмбеддингов Cloud.ru в коде не прошита и в документации
  не проверена: портал узнаёт её по первому ответу или берёт из
  `CLOUDRU_EMBEDDINGS_DIMENSION` и сверяет с каждым следующим ответом.
  Если модель вернёт вектор другой длины, портал откажет с текстом,
  а не запишет его.
- Повтора запроса на `401` нет — намеренно: ключ статический, и второй
  раз он ответит тем же. Для Сбера повтор есть, потому что там протухает
  токен, а не ключ.
- Корпус документов по-прежнему тот же, что и с Яндексом (issue #38):
  смена модели не добавляет материалов, она меняет только формулировку.
- Распознавание речи переключается отдельно (`VEDAL_STT_PROVIDER`, раздел
  выше), а синтез речи остаётся на SpeechKit Яндекса: у Cloud.ru
  Foundation Models модели синтеза нет, и для озвучивания ответов
  `VEDAL_SPEECHKIT_API_KEY` нужен и после переезда.
