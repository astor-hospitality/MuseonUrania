# Switching Vedalina to GigaChat (Sber)

[Русский](gigachat_activation.md) · **English**

## What the code already does

The model provider is chosen by a single variable — `VEDAL_LLM_PROVIDER`:
`yandex` (YandexGPT, as before) or `gigachat`. Nothing else changes: the
portal still finds the materials (`DeterministicSearch`, plus pgvector when
RAG is on), the model only receives the prepared context and puts it into
words. No sources — nothing is sent to the model, the visitor gets the regular
handoff to a human. The answer rules (no prices, diagnoses, lead times) are
the same for both providers: they live in `ModelEngine`, not in the model.

The default is `yandex`. A stand where the variable is not set keeps answering
the way it did after the update; switching is a deliberate change in `.env`.

What differs between Sber and Yandex from the outside:

| | YandexGPT | GigaChat |
| --- | --- | --- |
| Access | static Api-Key | authorization key → OAuth token for 30 minutes (refreshed by the portal) |
| Completion endpoint | `llm.api.cloud.yandex.net/foundationModels/v1/completion` | `gigachat.devices.sberbank.ru/api/v1/chat/completions` |
| Models | `yandexgpt-lite`, `yandexgpt` | `GigaChat`, `GigaChat-Pro`, `GigaChat-Max` |
| Embeddings | `text-search-doc` / `text-search-query` pair, 256 | one model `Embeddings` / `EmbeddingsGigaR`, 1024 |
| TLS | JVM default roots | needs the Russian Ministry of Digital Development root ("Russian Trusted Root CA") |

## Where to get the key

1. Sber developer studio: <https://developers.sber.ru/studio> → sign in with
   Sber ID → create a **GigaChat API** project (or open an existing one).
2. In the project — "Authorization key": shown once, it is the base64 of
   `client_id:client_secret`. Put it into `GIGACHAT_AUTH_KEY`. The pair
   (`GIGACHAT_CLIENT_ID`, `GIGACHAT_CLIENT_SECRET`) is an equivalent
   alternative; when both are set, the ready key wins.
3. Scope (`GIGACHAT_SCOPE`) follows the contract type: `GIGACHAT_API_PERS`
   (individuals, free token package — for trials), `GIGACHAT_API_B2B`
   (sole traders and companies, prepaid), `GIGACHAT_API_CORP` (companies,
   postpaid). A wrong scope gives `401` on the key exchange — the first thing
   to check when it fails.

Never keep the key in Jira, GitHub, email or the repository. Hand it over
through a protected channel only and put it into `backend/.env` on the VM
or into CI secrets — exactly like the Yandex key.

**Pricing.** The PERS package is free but limited per year and not for
commercial use; B2B/CORP are billed per token, and `GigaChat` is noticeably
cheaper per million tokens than `Pro` and `Max`. Vedalina's job is simple —
retell four found materials — so start with `GigaChat`; current prices are on
the GigaChat API page in the studio and are not copied here so they do not
go stale.

## The Ministry root certificate

Sber endpoints are signed by the "Russian Trusted Root CA" chain. The JVM
trust store (and the `eclipse-temurin` image) does not contain it, so the
connection fails with `PKIX path building failed`. The portal cannot disable
TLS verification — on purpose: materials and the key go through that door.

Steps:

1. Download the root certificate from the official Gosuslugi page
   (<https://www.gosuslugi.ru/crt>, "certificate for Linux", PEM).
2. Put it at `backend/certs/russian_trusted_root_ca.pem` **before building
   the image** — the Dockerfile copies the directory to `/app/certs/`.
   Details and file checks — `backend/certs/README.md`.
3. Point to it inside the container:
   `GIGACHAT_CA_BUNDLE=/app/certs/russian_trusted_root_ca.pem`.

The root is added to the JVM defaults, not substituted for them: Yandex,
Keycloak and mail keep being verified as before. If the file is missing at
the given path, the portal refuses to start and says where to get it.

## Environment variables

```env
VEDAL_ASSISTANT_ENGINE=model          # was yandexgpt — still accepted
VEDAL_LLM_PROVIDER=gigachat
GIGACHAT_AUTH_KEY=<authorization key of the GigaChat API project>
GIGACHAT_SCOPE=GIGACHAT_API_PERS      # or GIGACHAT_API_B2B / GIGACHAT_API_CORP
GIGACHAT_MODEL=GigaChat               # GigaChat-Pro, GigaChat-Max — no rebuild
GIGACHAT_CA_BUNDLE=/app/certs/russian_trusted_root_ca.pem

# Shared by any provider (the old VEDAL_YANDEXGPT_* names are still accepted):
VEDAL_LLM_TEMPERATURE=0.2
VEDAL_LLM_MAX_TOKENS=600
VEDAL_LLM_FALLBACK=true
```

The Yandex key is not needed with `gigachat` — and vice versa. A typo in
`VEDAL_LLM_PROVIDER` stops the startup with the list of allowed values
instead of silently falling back to Yandex.

The variables reach the container through `backend/compose.yaml` (the
"Vedalina" block of the `portal` service) — a value in `.env` does not get
inside on its own.

## How to switch and how to roll back

Switching is three lines in `backend/.env` (`VEDAL_LLM_PROVIDER=gigachat`,
`GIGACHAT_AUTH_KEY`, `GIGACHAT_CA_BUNDLE`), then:

```bash
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml --profile app up -d --build
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml logs -f portal | grep -i -E "Ведалина|GigaChat"
```

The startup log must say "Ведалина отвечает моделью GigaChat …"; on the first
question — "Токен GigaChat получен, действует до …".

Rollback — `VEDAL_LLM_PROVIDER=yandex` (or remove the variable: the default is
Yandex) and the same `up -d`. The Yandex keys need not be removed from `.env`
when switching: they do no harm and make the rollback a one-line change.

## Smoke test after switching

```bash
curl -sS https://<domain>/api/assistant/v1/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"какой инкубатор есть для новорождённых?"}'
```

The response must contain `answer` with Vedalina's text, a non-empty
`sources` and no `handoff` when a published source was found. The answer
streams in parts (`draft`) — just like with Yandex.

If the answer is a list of links without prose, the model did not answer and
`VEDAL_LLM_FALLBACK=true` kicked in: the reason is in the portal log
(`GigaChat ответил 401` — key or scope; `PKIX` — certificate; `429` — token
limit of the package).

## Vector search (pgvector) and re-indexing

Embeddings have their own provider — `VEDAL_RAG_PROVIDER`. By default it
inherits `VEDAL_LLM_PROVIDER`, but it can be set separately, and usually
**that is what you want**: move generation to GigaChat today, keep the index
on Yandex.

The reason is dimension. The `knowledge_chunk.embedding` column was created by
migration V34 as `vector(256)` for Yandex models; GigaChat embeddings are
longer (`GIGACHAT_EMBEDDINGS_DIMENSION`, 1024 by default). The portal compares
the model with the column at startup and refuses to start on a mismatch —
with a message saying what to do — instead of failing with an SQL error on the
first indexing run.

If RAG is off (`VEDAL_RAG_ENABLED=false`, the default), none of this applies:
there is no index and the embeddings provider is not used.

Moving the index to GigaChat is a separate job, not part of switching
generation:

1. A new Flyway migration in `backend/app/src/main/resources/db/migration/`
   that clears the chunks and changes the column type (old vectors do not fit
   the new column, and there is nothing to compare them with anyway):

   ```sql
   -- V<N>__knowledge_vectors_gigachat.sql
   truncate knowledge_chunk, knowledge_source;
   drop index if exists knowledge_chunk_embedding_idx;
   alter table knowledge_chunk alter column embedding type vector(1024);
   create index knowledge_chunk_embedding_idx
       on knowledge_chunk using hnsw (embedding vector_cosine_ops);
   ```

   `truncate` runs as the schema owner during migration, not as the runtime
   role: the application role has `TRUNCATE` revoked (V34), which does not
   get in the way.
2. `VEDAL_RAG_PROVIDER=gigachat`, `GIGACHAT_EMBEDDINGS_MODEL=Embeddings`
   (or `EmbeddingsGigaR`), `GIGACHAT_EMBEDDINGS_DIMENSION=1024`.
3. After startup — full re-indexing from the admin panel ("Vedalina index",
   the re-index button): chunks are recomputed by the Sber model and marked
   in the `model` column as `gigachat/Embeddings`.
4. Recalibrate `VEDAL_RAG_MAX_DISTANCE` — distances differ between models
   (the procedure is in the [Vedalina pipeline](vedalina_rag_pipeline.en.md)).

Rolling the index back — the reverse migration (`vector(256)`) and
re-indexing with Yandex.

## Limitations

- The 1024 dimension for Sber models comes from the GigaChat API documentation
  and is not hard-coded — it is a setting checked against every response. If
  the model returns a vector of another length, the portal refuses with a
  message instead of storing it.
- The document corpus is the same as with Yandex (issue #38): changing the
  model adds no materials, it only changes the wording.
- Voice input (SpeechKit) stays on Yandex — it is not part of the model and
  is not affected by this switch.
