# Vedalina: how the RAG corpus is built

[Русский](vedalina_rag_pipeline.md) · **English**

Recorded on 2026-08-27, updated on 2026-09-08.

At the MVP stage VEDAL has no separate private document corpus. Vedalina's
knowledge corpus is therefore built from the public website and from every
material Michael uploads to S3 / the portal for VEDAL.

## Where documents go

A document cannot be dropped straight into S3 and considered searchable.
Two parts are required:

1. the file in Object Storage;
2. the document card in PostgreSQL.

The correct upload points:

| Material | Where it goes | What must appear in the database |
| --- | --- | --- |
| Photos, background images, site media | `vedal-media` | a link in the page, product or news card |
| PDFs, catalogs, brochures, licences, product descriptions | `vedal-documents` | a `document` row: title, product, language, type, status, `storage_key` |
| Website page text | CMS / admin area | a page / news / product row |

On the stand, uploads must go through the admin area or the import script.
Then the portal itself puts the file into S3, stores `storage_key` in
PostgreSQL and hands the material to Vedalina for indexing.

## How the pipeline works

```text
Admin area / import
  -> S3: the file
  -> PostgreSQL: the document card
  -> indexing queue
  -> text extraction from PDF/DOCX/HTML
  -> chunks of 500-1000 tokens with overlap
  -> embeddings
  -> PostgreSQL + pgvector
  -> top-k chunk search
  -> YandexGPT receives the question plus the chunks found
  -> Vedalina's answer with links to the sources
```

## PostgreSQL and pgvector

Done. A single PostgreSQL on the `pgvector/pgvector:pg16` image — the same
PostgreSQL 16 as before, plus the extension. The schema is created by the
`V34__knowledge_vectors.sql` migration, whose first statement is
`create extension if not exists vector`. In Managed PostgreSQL the extension is
enabled from the cloud console, and there that statement is a no-op.

### Switching the image on a live volume: the collation warning

Caught on 8 September the first time the stack came up on the new image, and
it will repeat on the stand — the volume there holds data, not nothing:

```
WARNING: database "vedal" has a collation version mismatch
DETAIL:  The database was created using collation version 2.41,
         but the operating system provides version 2.36.
```

What happened. The `postgres:16` image and the `pgvector/pgvector:pg16` image
are built on different versions of the system library, and string sort order
comes from it. The volume is the same and PostgreSQL is the same version, but
the rule for "what comes first alphabetically" changed underneath. Indexes on
text columns were built under the old rule, and from this moment the database
knows they may lie: searching through an index sorted differently from how the
database itself compares means rows missing from results, not an error on
screen.

The warning itself neither fixes nor breaks anything: it repeats on every
query and waits for a person to decide.

The cure is two commands, in this order:

```bash
docker exec vedal-db psql -U vedal -d vedal -c "REINDEX DATABASE vedal;"
docker exec vedal-db psql -U vedal -d vedal -c "ALTER DATABASE vedal REFRESH COLLATION VERSION;"
```

Rebuild the indexes under the new rule first, and only then tell the database
the version is accepted. The reverse order clears the warning while leaving the
indexes stale — that is, it hides exactly what the warning was about.

On the stand `REINDEX DATABASE` locks the tables while it runs. The database
there is small and it takes seconds, but it belongs in the same window as the
deploy, not in front of live visitors.

Verified locally: after the two commands the warning is gone and the data is
intact.

Two tables.

`knowledge_source` — a portal material:

| Column | What it holds |
| --- | --- |
| `kind` | `product`, `news`, `document`, `page` |
| `external_id` | the material's slug in its own module; unique together with `kind` |
| `title`, `url` | what to show under the answer and where to send the reader |
| `visibility` | `public` or `internal` |
| `language` | the material's language |
| `checksum` | fingerprint of the text: unchanged means no reindexing |
| `indexed_at` | when it was indexed |

`knowledge_chunk` — a fragment and its vector:

| Column | What it holds |
| --- | --- |
| `source_id` | the material; deleting it cascades to the chunks |
| `position` | the fragment's number within the material |
| `text` | the fragment itself — exactly what reaches the model's context |
| `model` | which model produced the vector |
| `embedding` | `vector(256)` |

Two indexes: `hnsw (embedding vector_cosine_ops)` for similarity search and a
plain one on `source_id` for reindexing. HNSW rather than IVFFlat: the latter
builds its lists from existing data and is meaningless on an empty table — it
would have to be created by a separate migration on the day the corpus arrives.

What the schema deliberately lacks:

- **a `confidential` level.** §7.4 hands such documents out "only by separate
  permission", and a staff login is not a separate permission. Such material
  does not enter the index at all — it is not filtered out on retrieval, it is
  physically absent. A retrieval filter would eventually be forgotten in some
  new query; a row that does not exist cannot be forgotten;
- **a second column for another dimension.** Vectors from different models are
  incomparable, and there is nothing to compare them with anyway. Changing the
  embedding model means a separate migration and a full reindex.

`checksum` is computed over the text together with the title, the URL and the
**model name**: a PDF's metadata and date change while its text stays the same,
so a fingerprint over the file bytes would force reindexing of unchanged
material. The model name is in the fingerprint so that changing the model does
not leave the old vectors in the index as dead weight.

## Dimension and model

The embeddings provider is `VEDAL_RAG_PROVIDER`: `yandex` (the default,
inherited from `VEDAL_LLM_PROVIDER`) or `gigachat`. Below is about Yandex;
Sber and the re-indexing required when switching providers are in
[gigachat_activation.en.md](gigachat_activation.en.md#vector-search-pgvector-and-re-indexing).

Embeddings come from Yandex Foundation Models, the
`foundationModels/v1/textEmbedding` endpoint, using the same key as YandexGPT.

There are **two** models, and that is not duplication: `text-search-doc`
encodes document fragments, `text-search-query` encodes questions. They are a
pair — the vectors land in one space precisely because the models differ.
Mixing them up is not a failure but a quietly corrupted result set.

The vector length is 256, and that number is baked into the column type:
pgvector requires a dimension, otherwise the column cannot be indexed. The
portal does not take the number on trust — the length of the returned vector is
checked against the expected one, and a mismatch fails the indexing with a
readable message. Otherwise a model change would show up not as a failure but
as answers getting worse.

## How this relates to YandexGPT

YandexGPT does not store our documents. It only receives temporary context at
the moment of answering:

1. a visitor writes a question;
2. the backend turns the question into an embedding;
3. PostgreSQL/pgvector finds the nearest chunks;
4. the backend assembles the prompt: Vedalina's rules + the excerpts found + the question;
5. YandexGPT generates an answer;
6. the backend checks the guardrails and returns the answer to the site.

If there are no similar chunks, Vedalina does not invent an answer — she hands
the question over to a specialist.

## What is indexed today

Today, before the corpus, what gets indexed is what the portal already shows:
published products, news and document cards. It is taken through the same
neighbour query interfaces as the word search uses — not a single new field and
not a single new source.

Files too: the text of a published PDF or DOCX is extracted and appended to the
document card. A scan yields empty text — such a document is indexed as a card
alone and costs not a single model call.

Indexed:

- public website pages;
- published products;
- news;
- documents uploaded for the site;
- PDFs, brochures and catalogs Michael adds to S3 and registers in the portal.

Not indexed:

- passwords, keys, contractual secrets and accounts;
- drafts, unless marked as site material;
- personal data from leads;
- chat logs and the service audit trail.

If VEDAL later hands over restricted documents, we introduce a second indexing
contour with access rights. At this stage the whole corpus handed over for the
site is treated as Vedalina's public corpus.

## Which ports are needed

RAG does not require exposing PostgreSQL, Kafka or S3 keys to the outside.

Only these need to face outward:

- site/API: `18080` on the current stand, then `443`;
- Keycloak for staff sign-in: `18180` temporarily, then HTTPS over the domain;
- SSH: `2222`, restricted to exact IPs / VPN.

Everything else works inside the VM or over outbound HTTPS:

- the backend reads S3 with a service key;
- the backend calls YandexGPT over HTTPS;
- PostgreSQL/pgvector lives in the Docker network;
- Kafka and the indexing queue live in the Docker network.

## What is done and what waits for the corpus

State as of 9 September 2026,
[issue #38](https://github.com/michaelwelly/MuseonUrania/issues/38).

Done — everything that does not depend on the documents' content:

1. PostgreSQL moved to an image with `pgvector`; the tests start the same image.
2. Migration `V34` creates `knowledge_source` and `knowledge_chunk`.
3. Chunking with overlap (`Chunks`).
4. The `Embeddings` port and its `YandexEmbeddings` implementation.
5. Indexing with a checksum (`KnowledgeIndex`): unchanged material costs not a
   single model call.
6. Similarity search with a threshold and the `PUBLIC` / `STAFF` scopes
   (`VectorSearch`).
7. A handover rather than a replacement (`RagRetrieval`): nothing found in the
   index means the previous word search answers.
8. Reindexing of what the portal already shows — products, news and document
   cards.
9. **Text extraction from PDF and DOCX** (`FileText`). PDFBox parses PDF; the
   portal reads DOCX itself — it is a ZIP with a single XML part, and an
   office-format library is not worth the image size for it. The file comes
   from whoever uploaded it, so both roads are fenced: XML external entities
   are off, the unpacked size and the text length are capped. There is no OCR:
   a scan yields empty text, and that is an honest answer — such a document is
   indexed as a card.
10. **The indexing queue** (`KnowledgeIndexer`). The `vedal.documents.v1` event
    triggers reindexing of a single document and is now written not only on
    publication but on card edits and file replacement too — in the same
    transaction as the edit itself. The consumer re-reads the listing: the
    document is there, index it; it is not, forget it.
11. **The "reindex" button in the admin area** and the index state next to it:
    `GET` and `POST /api/admin/v1/knowledge`, a card on the documents page.
    Each document row shows whether Vedalina searches it and with how many
    chunks.

Waiting for the corpus:

1. **Calibration of the `vedal.assistant.rag.max-distance` threshold.** The
   default of 0.45 is deliberately provisional: a threshold can only be measured
   against real documents and real questions. How to measure it — see below.
2. **A second indexing circuit** for restricted material, should VEDAL hand any
   over.

## How to calibrate the threshold once the corpus exists

`vedal.assistant.rag.max-distance` is the cosine distance beyond which a chunk
counts as unrelated to the question. It is a single number with two failure
modes of very different cost:

- **the threshold is too large** — unrelated chunks reach the model's context
  and Vedalina answers confidently and beside the point. This is the worst
  failure available: the visitor cannot see that the answer was assembled from
  something unrelated;
- **the threshold is too small** — the assistant hands the question to a human
  where the corpus did hold an answer. Expensive, but visible and not a lie.

Hence the rule of calibration: **when in doubt, take the smaller threshold.**

The procedure:

1. **Build the corpus and the index.** Documents are uploaded through the admin
   area (the file into S3 **and** a card in the database), then the "reindex"
   button.
2. **Assemble a list of questions.** Thirty to fifty real questions — not
   invented by us but taken from client correspondence, from leads and from
   Vedalina's conversation log. Half must have an answer in the corpus, half
   must deliberately not. The second half matters more: it is what exposes a
   threshold that is too large.
3. **Record the distances.** For every question, note the distance to the
   nearest chunk and whether a human considers that chunk an answer. During the
   measurement the threshold is set deliberately high (0.9, say), otherwise
   some of the measurements never appear.
4. **Look at the two clouds of numbers.** Questions with an answer cluster
   lower, questions without cluster higher. The threshold goes into the gap
   between them, closer to the lower cloud.
5. **Verify on held-out questions.** A dozen questions that took no part in the
   measurement. If the threshold behaves differently on them, the clouds
   overlap and the threshold is not the problem: the chunking or the embedding
   model does not suit this corpus.

What not to do:

- **pick the threshold by feel from a handful of questions.** A single number
  chosen from three examples is a coincidence, not a threshold;
- **raise the threshold so that "something is found more often".** Vedalina
  does not fall silent on an empty result: she hands the question to a human.
  Raising the threshold turns "nothing found" into "the wrong thing found",
  and the second is the more expensive of the two;
- **measure on synthetic text.** Distances depend on how the real text is
  built: a datasheet full of tables and a news item behave differently.

The result is written down here and into `VEDAL_RAG_MAX_DISTANCE` on the stand.
No reindexing is needed after changing the threshold — it applies at query time,
not at indexing time.

## The empty index

This is the key property of what has been built, and it is covered by tests.

An empty index is a working state, not a placeholder. Searching it returns zero
rows, `RagRetrieval` hands the question to the word search, and the assistant
answers exactly as it did before pgvector. No failure, no invented answer.

An empty index also costs nothing: before turning the question into a vector —
which is a model call, that is, a bill — the portal asks the database whether
there is anything to search at all. Until the corpus exists, that is the only
query added to the previous behaviour.

The same goes for the cloud falling silent: an embeddings failure means "the
vector search found nothing", not "the assistant is broken".

## How to switch it on

Off by default. There is nothing to index, and every question would cost an
embeddings call for a knowingly empty result.

```env
VEDAL_RAG_ENABLED=true
VEDAL_RAG_DOCUMENT_MODEL_URI=emb://<folder_id>/text-search-doc/latest
VEDAL_RAG_QUERY_MODEL_URI=emb://<folder_id>/text-search-query/latest
VEDAL_RAG_MAX_DISTANCE=0.45
```

With `VEDAL_RAG_PROVIDER=gigachat` the pair of model addresses is not needed;
`GIGACHAT_EMBEDDINGS_MODEL` and the `GIGACHAT_AUTH_KEY` key are used instead.

No separate key is needed — `VEDAL_YANDEXGPT_API_KEY` is used: embeddings live
in the same Foundation Models and are billed to the same service account.

A half-configured setup fails the startup with a readable message: an enabled
mode without a key is a portal quietly working at half capacity, and the only
way to notice would be the answers getting worse.
