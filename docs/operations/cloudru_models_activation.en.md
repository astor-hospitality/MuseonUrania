# Switching Vedalina to Cloud.ru Foundation Models

[Русский](cloudru_models_activation.md) · **English**

## What the code already does

The model provider is chosen by a single variable — `VEDAL_LLM_PROVIDER`:
`yandex` (YandexGPT, as before), `gigachat` (Sber directly) or `cloudru` —
Cloud.ru Evolution Foundation Models. Nothing else changes: the portal still
finds the materials (`DeterministicSearch`, plus pgvector when RAG is on), the
model only receives the prepared context and puts it into words. No sources —
nothing is sent to the model, the visitor gets the regular handoff to a human.
The answer rules (no prices, diagnoses, lead times) are the same for every
provider: they live in `ModelEngine`, not in the model.

The default is `yandex`. A stand where the variable is not set keeps answering
the way it did after the update; switching is a deliberate change in `.env`.

Why a third provider when GigaChat is already there: Cloud.ru serves the same
Sber models (and open models) through an OpenAI-compatible endpoint with a
static key — no OAuth exchange, no scope, no Ministry root certificate. It is
simpler to enable and simpler to debug; billing goes through the Cloud.ru
contract.

What differs from the outside:

| | YandexGPT | GigaChat (Sber) | Cloud.ru Foundation Models |
| --- | --- | --- | --- |
| Access | static Api-Key | authorization key → OAuth token for 30 minutes | static API key, `Authorization: Bearer` |
| Completion endpoint | `llm.api.cloud.yandex.net/foundationModels/v1/completion` | `gigachat.devices.sberbank.ru/api/v1/chat/completions` | `foundation-models.api.cloud.ru/v1/chat/completions` |
| Models | `yandexgpt-lite`, `yandexgpt` | `GigaChat`, `GigaChat-Pro`, `GigaChat-Max` | `GigaChat/GigaChat-2-Max`, `GigaChat/GigaChat-3-Pro` and others from `/v1/models` |
| Embeddings | `text-search-doc` / `text-search-query` pair, 256 | `Embeddings` / `EmbeddingsGigaR`, 1024 | `ai-sage/Giga-Embeddings-instruct-480M` / `-3B`, dimension learned from the first answer |
| TLS | JVM default roots | needs the Ministry root | JVM default roots |

The request body and the stream parsing are shared between Sber and Cloud.ru
(`OpenAiChat`): the OpenAI schema, an SSE stream of `data: {...}` lines with
deltas and `data: [DONE]` at the end.

## Where to get the key

1. Cloud.ru console: <https://console.cloud.ru> → sign in → the
   **Evolution Foundation Models** service.
2. In the service — the **API keys** section → create a key. The console
   shows it once; put it into `CLOUDRU_API_KEY`. One key serves both
   generation and embeddings.
3. The available models and their identifiers are in the service catalogue;
   the endpoint `GET /v1/models` returns the same list with that key:

   ```bash
   curl -sS https://foundation-models.api.cloud.ru/v1/models \
     -H "Authorization: Bearer $CLOUDRU_API_KEY" | jq -r '.data[].id'
   ```

Never keep the key in Jira, GitHub, email or the repository. Hand it over
through a protected channel only and put it into `backend/.env` on the VM
or into CI secrets — exactly like the Yandex and Sber keys.

**Pricing.** Foundation Models are billed per token under the Cloud.ru
contract; `GigaChat-2-Max` and `GigaChat-3-Pro` are priced differently, and
embeddings have their own rates. Vedalina's job is simple — retell four found
materials — so if cost matters, look in the catalogue for a cheaper model of
the same family. Current prices are on the service page in the console and are
not copied here so they do not go stale.

## Environment variables

```env
VEDAL_ASSISTANT_ENGINE=model          # was yandexgpt — still accepted
VEDAL_LLM_PROVIDER=cloudru
CLOUDRU_API_KEY=<API key from the Cloud.ru console>
CLOUDRU_MODEL=GigaChat/GigaChat-2-Max # GigaChat/GigaChat-3-Pro and others — no rebuild
# CLOUDRU_BASE_URL=https://foundation-models.api.cloud.ru/v1   # default, no reason to change

# Shared by any provider (the old VEDAL_YANDEXGPT_* names are still accepted):
VEDAL_LLM_TEMPERATURE=0.2
VEDAL_LLM_MAX_TOKENS=600
VEDAL_LLM_FALLBACK=true
```

The Yandex and Sber keys are not needed with `cloudru` — and vice versa.
A typo in `VEDAL_LLM_PROVIDER` stops the startup with the list of allowed
values instead of silently falling back to Yandex. An empty or damaged
`CLOUDRU_API_KEY` (spaces, Cyrillic from copy-paste) also stops the startup
with a message saying where to get the key.

The variables reach the container through `backend/compose.yaml` (the
"Vedalina" block of the `portal` service) — a value in `.env` does not get
inside on its own.

## How to switch and how to roll back

Switching is two lines in `backend/.env` (`VEDAL_LLM_PROVIDER=cloudru`,
`CLOUDRU_API_KEY`), then:

```bash
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml --profile app up -d --build
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml logs -f portal | grep -i -E "Ведалина|Cloud.ru"
```

The startup log must say "Ведалина отвечает моделью Cloud.ru
GigaChat/GigaChat-2-Max". There is no key-for-token exchange, so there is no
token line as with Sber.

Rollback — `VEDAL_LLM_PROVIDER=yandex` (or `gigachat`, or remove the variable:
the default is Yandex) and the same `up -d`. The other providers' keys need not
be removed from `.env` when switching: they do no harm and make the rollback a
one-line change.

## Smoke test after switching

```bash
curl -sS https://<domain>/api/assistant/v1/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"какой инкубатор есть для новорождённых?"}'
```

The response must contain `answer` with Vedalina's text, a non-empty
`sources` and no `handoff` when a published source was found. The answer
streams in parts (`draft`) — just like with Yandex and Sber.

If the answer is a list of links without prose, the model did not answer and
`VEDAL_LLM_FALLBACK=true` kicked in: the reason is in the portal log
(`Cloud.ru ответил 401` — the key; `404` — no such model, check
`CLOUDRU_MODEL` against `/v1/models`; `429` — the plan's request limit).

## Vector search (pgvector) and re-indexing

Embeddings have their own provider — `VEDAL_RAG_PROVIDER`. By default it
inherits `VEDAL_LLM_PROVIDER`, but it can be set separately, and usually
**that is what you want**: move generation to Cloud.ru today, keep the index
on Yandex.

The reason is dimension. The `knowledge_chunk.embedding` column was created by
migration V34 as `vector(256)` for Yandex models. The vector length of
`Giga-Embeddings-instruct` is not stated in the Cloud.ru documentation, so the
portal does not assume it: with an empty `CLOUDRU_EMBEDDINGS_DIMENSION` it
sends a probe request at startup, learns the length from the first answer and
checks the column against it — on a mismatch it refuses to start, with a
message saying what to do. A given number is checked against every answer,
the same way as with Sber.

If RAG is off (`VEDAL_RAG_ENABLED=false`, the default), none of this applies:
there is no index and the embeddings provider is not used.

Moving the index to Cloud.ru is a separate job, not part of switching
generation:

1. Find out the model's dimension — one request with the same key:

   ```bash
   curl -sS https://foundation-models.api.cloud.ru/v1/embeddings \
     -H "Authorization: Bearer $CLOUDRU_API_KEY" -H 'Content-Type: application/json' \
     -d '{"model":"ai-sage/Giga-Embeddings-instruct-480M","input":["размерность"]}' \
     | jq '.data[0].embedding | length'
   ```

2. A new Flyway migration in `backend/app/src/main/resources/db/migration/`
   that clears the chunks and changes the column type to that length (old
   vectors do not fit the new column, and there is nothing to compare them
   with anyway):

   ```sql
   -- V<N>__knowledge_vectors_cloudru.sql
   truncate knowledge_chunk, knowledge_source;
   drop index if exists knowledge_chunk_embedding_idx;
   alter table knowledge_chunk alter column embedding type vector(<размерность>);
   create index knowledge_chunk_embedding_idx
       on knowledge_chunk using hnsw (embedding vector_cosine_ops);
   ```

   `truncate` runs as the schema owner during migration, not as the runtime
   role: the application role has `TRUNCATE` revoked (V34), which does not
   get in the way. The pgvector HNSW index is limited to 2000 dimensions: if
   the model returns more, the index cannot be created and another embeddings
   model has to be chosen.
3. `VEDAL_RAG_PROVIDER=cloudru`, `CLOUDRU_EMBEDDINGS_MODEL=ai-sage/Giga-Embeddings-instruct-480M`
   (or `-3B`), `CLOUDRU_EMBEDDINGS_DIMENSION=<dimension>` — an explicit number
   is better than an empty one: the startup does not spend a probe request,
   and the promise is recorded in `.env` next to the migration.
4. After startup — full re-indexing from the admin panel ("Vedalina index",
   the re-index button): chunks are recomputed by the Cloud.ru model and marked
   in the `model` column as `cloudru/ai-sage/Giga-Embeddings-instruct-480M`.
5. Recalibrate `VEDAL_RAG_MAX_DISTANCE` — distances differ between models
   (the procedure is in the [Vedalina pipeline](vedalina_rag_pipeline.en.md)).

Rolling the index back — the reverse migration (`vector(256)`) and
re-indexing with Yandex.

## Limitations

- The Cloud.ru embeddings dimension is neither hard-coded nor verified against
  the documentation: the portal learns it from the first answer or takes it
  from `CLOUDRU_EMBEDDINGS_DIMENSION` and checks every following answer
  against it. If the model returns a vector of another length, the portal
  refuses with a message instead of storing it.
- There is no retry on `401` — on purpose: the key is static, and a second
  attempt would get the same answer. Sber has a retry because there the token
  expires, not the key.
- The document corpus is the same as with Yandex (issue #38): changing the
  model adds no materials, it only changes the wording.
- Voice input (SpeechKit) stays on Yandex — it is not part of the model and
  is not affected by this switch.
