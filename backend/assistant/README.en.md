# assistant

[Русский](README.md) · **English**

The public assistant Vedalina. The specification is
[vedalina_assistant_spec.en.md](../../docs/strategy/vedalina_assistant_spec.en.md),
its place in the architecture is the
[backend spec](../../docs/superpowers/specs/2026-08-06-vedal-portal-architecture-design.en.md).

Code: `src/main/java/ru/vedal/portal/assistant/`.

There are two doors: `POST /api/assistant/v1/ask` — a question, the response
carries text and a list of sources; `GET /api/assistant/v1/prompts` — the
quick-reply buttons for the widget.

## Why the buttons come from the portal

Button labels and their canned answers used to live in the interface.
Pressing one sent the label as a question — and the search answered
«Запросить КП» with a list of products whose descriptions happened to
contain a similar word. The «Специалист VEDAL» button called nobody.

The widget now sends `intent` — which button was pressed — instead of
relying on string equality: a label lives in the interface and changes with
it, and matching on it would drift silently. The texts are in
`ScriptedReplies`.

Free text deliberately does not reach them. Guessing intent from words
(«хочу КП» → the quote script) misfires on negation: «пока не нужно КП»
would get the same answer.

## Why the limits are not in the prompt

The hard prohibitions — no diagnosis, no treatment advice, no invented prices,
delivery times, specifications or registration statuses — live in `Guardrails`,
before the engine is called. A prompt is a request to the model, not a guarantee;
a check placed before the engine does not depend on which model sits behind the
port.

A separate trap: in Java the `\b` word boundary is governed by
`UNICODE_CHARACTER_CLASS`, that is the `(?U)` flag. With a lowercase `u`
(`UNICODE_CASE`) the Cyrillic rules **silently fail**. `GuardrailsTest` checks
exactly this.

## Why closed materials are unreachable

`LlmEngine` only goes through its neighbours' interfaces — `CatalogQuery`,
`ContentQuery`, `DocumentQuery` — and those return published items exclusively.
An unpublished product, a draft news item and an unapproved document physically
cannot enter the answer context, so the assistant cannot be talked into showing
them.

No suitable sources means no answer: it hands off to a human with contacts and a
list of forms. Inventing an answer is forbidden.

## Who answers: the search or the model

Two implementations stand behind the `LlmEngine` port, selected by the
`vedal.assistant.engine` setting:

| Value | Who answers |
| --- | --- |
| `search` (default) | `DeterministicSearch` — a list of what was found, with links |
| `model` (old `yandexgpt`) | `ModelEngine` — a model on top of that same search |

Whose model — `vedal.assistant.provider` (`VEDAL_LLM_PROVIDER`): `yandex` —
`YandexGptHttp` (default), `gigachat` — `GigaChatHttp` (Sber: a 30-minute
OAuth token in `GigaChatAuth`, SSE stream, the Ministry root via
`TrustedCertificates`), `cloudru` — `CloudRuHttp` (Cloud.ru Evolution
Foundation Models: the same GigaChat models behind an OpenAI-compatible
endpoint, a static key, no OAuth and no certificate; the request body and SSE
parsing are shared with Sber — `OpenAiChat`). All three sit behind the
`ChatModel` port, and `ModelEngine` does not know whom it talks to.
Activation — `docs/operations/yandexgpt_activation.en.md`,
`docs/operations/gigachat_activation.en.md` and
`docs/operations/cloudru_models_activation.en.md`.

**The model does not replace the search, it sits on top of it.** The portal
finds the materials: it has the catalogue, the news and the documents with
permissions applied, while the model has nothing except what we show it. If
nothing was found, the model is not asked at all: the rule "no published
sources, no answer" outweighs the urge to say something, and paying a model
to reply "I do not know" is paying for a refusal.

**Links are never asked of the model.** The sources under an answer are exactly
the materials the search found, in the same order. Let the model name them
itself and one day it will name a plausible page that does not exist. It only
places the markers `[1]`, `[2]` in the text; where a marker leads is decided by
the portal, and the widget turns it into a link.

**A silent model does not stop the conversation.** A cloud refusal, a timeout,
an empty reply — the list of found materials is returned instead, the very one
that existed before the model. The materials were found after all; making the
visitor wait for a human because of someone else's downtime is pointless.

**No key lives in the repository.** `VEDAL_YANDEXGPT_API_KEY` and
`VEDAL_YANDEXGPT_MODEL_URI` (the full model address, `gpt://folder/model`) —
or `GIGACHAT_AUTH_KEY` for Sber, `CLOUDRU_API_KEY` for Cloud.ru — come from
the environment, and with `engine=model`
the portal will not start without them — deliberately: otherwise it would
quietly answer with a list of links, and the substitution would only be
noticeable by the answers becoming drier.

## Similarity search: pgvector

The portal searches, the model phrases — two different jobs. The search now
sits behind its own `Retrieval` port, with two implementations:

| Implementation | How it searches |
| --- | --- |
| `DeterministicSearch` | by words in names and descriptions |
| `VectorSearch` | by vector proximity in the pgvector index |

Swapping the implementation touches neither the prompt, nor the numbering of
sources, nor the conversation: `ModelEngine` receives a list of passages
and does not ask where they came from.

**An empty index is a working state, not a placeholder.** VEDAL has no
document corpus yet ([issue #38](https://github.com/michaelwelly/MuseonUrania/issues/38)),
and `RagRetrieval` is built as a handover rather than a replacement: nothing
found in the index means the previous word search answers. That is why the
vector search can be switched on before the corpus exists without the
assistant falling silent.

An empty index also costs nothing: before turning the question into a vector —
which is a model call, that is, a bill — the portal asks the database whether
there is anything to search at all.

**The two result sets are not merged.** Distance and word-match weight are
numbers from different scales; adding them requires an invented coefficient,
and an invented coefficient is a ranking nobody has verified. Hybrid retrieval
becomes meaningful work once there is a corpus to measure it on.

**What is already there:**

- the `knowledge_source` / `knowledge_chunk` schema and the `vector` extension
  (migration `V34`); the database image is `pgvector/pgvector:pg16`;
- chunking with overlap (`Chunks`);
- the `Embeddings` port and its implementations `YandexEmbeddings`,
  `GigaChatEmbeddings` and `CloudRuEmbeddings` (provider — `VEDAL_RAG_PROVIDER`;
  dimensions differ, and `KnowledgeStore` checks the model against the column
  at startup; with Cloud.ru the dimension is learned from the model's first
  answer) — Yandex uses the `text-search-doc` / `text-search-query` pair,
  a vector of 256 numbers;
- indexing with a checksum: unchanged material costs not a single model call
  (`KnowledgeIndex`);
- similarity search with a threshold and the `PUBLIC` / `STAFF` scopes
  (`VectorSearch`);
- reindexing of what the portal already shows — products, news and document
  cards (`KnowledgeIndex.reindexPublished`);
- text extraction from PDF and DOCX (`FileText`): the text of a published file
  is appended to the document card. PDF is parsed by PDFBox, DOCX by the portal
  itself (it is a ZIP with a single XML part, and an office-format library is
  not worth the image size for it). Scans, broken and encrypted files do not
  break reindexing: such a document enters the index as a card, and the reason
  goes to the log;
- the indexing queue (`KnowledgeIndexer`): the `vedal.documents.v1` event
  triggers reindexing of that one document. The event is written in the same
  transaction as the edit, so "the document is published" and "it must be
  reindexed" cannot drift apart. An unpublished document leaves the index the
  same way it leaves the site;
- the "reindex" button in the admin panel and the index state next to it:
  `GET` and `POST /api/admin/v1/knowledge`. The entry point answers even with
  indexing switched off — `enabled: false`, not a 500.

**What is missing and waits for the corpus:**

- calibration of the `vedal.assistant.rag.max-distance` threshold — it can only
  be measured against real documents and real questions;
- a second indexing circuit for restricted material. Today only `public` goes
  into the index; `confidential` never will — it does not even exist as a value
  in the schema.

It is switched on by `vedal.assistant.rag.enabled` together with the pair of
embedding model addresses. A half-configured setup fails the startup with a
readable message — for the same reason `engine=model` without a key does.

The scripted replies stay as the fast path for buttons: «Запросить КП» has a
known answer and does not need a model call.

What the search counts as a match: the brand (`vedal`, `ведал`) does not —
it stands in every product name, so it finds everything. A match in the name
weighs three times a match in the description, and there is a threshold:
one incidental word in a description is not enough. Failing the threshold is
a normal outcome — the conversation goes to a human; a product list assembled
from the word «для» is worse than an honest "I do not know".

The log records the outcome of a request and the number of sources, **without the
text of the question**: a visitor may name both a clinic and themselves in it.

## Build module

`portal-assistant` is a Maven module with its own `pom.xml` and its own `src/`.
It depends on: `common`, `audit`, `catalog`, `content`, `documents`.

Only the neighbours' query interfaces — `CatalogQuery`, `ContentQuery`,
`DocumentQuery`. They return published material only, which is why closed
materials are physically unreachable for Vedalina.

The boundary is enforced by the build rather than by discipline: importing
from a module that is not among the dependencies fails compilation. Previously
all the code sat in one heap under `backend/src/`, and the boundaries held
only as long as someone was paying attention.
