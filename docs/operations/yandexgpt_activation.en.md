# Switching Vedalina to YandexGPT

[Русский](yandexgpt_activation.md) · **English**

## What the code already does

`VEDAL_ASSISTANT_ENGINE=model` with the provider `VEDAL_LLM_PROVIDER=yandex`
(the default; the old `VEDAL_ASSISTANT_ENGINE=yandexgpt` is still accepted)
turns YandexGPT into a wording layer on top of the local safe search. The
second provider is Sber's GigaChat, the third is Cloud.ru Foundation Models;
both are switched by the same variable:
[gigachat_activation.en.md](gigachat_activation.en.md),
[cloudru_models_activation.en.md](cloudru_models_activation.en.md). `DeterministicSearch` picks only the sources allowed for
the current contour first; the model then receives an already prepared
`APPROVED_CONTEXT`.

If there are no sources, nothing is sent to YandexGPT at all: the visitor gets
the regular handoff to a human.

## Environment variables

```env
VEDAL_ASSISTANT_ENGINE=model
VEDAL_LLM_PROVIDER=yandex
VEDAL_YANDEXGPT_MODEL_URI=gpt://<folder_id>/yandexgpt/latest
VEDAL_YANDEXGPT_API_KEY=<service account api key>
VEDAL_LLM_TEMPERATURE=0.2
VEDAL_LLM_MAX_TOKENS=600
VEDAL_LLM_FALLBACK=true
```

Temperature, the token limit and the behaviour when the model is silent are
shared by both providers; the old names `VEDAL_YANDEXGPT_TEMPERATURE`,
`_MAX_TOKENS`, `_FALLBACK` are still accepted.

Never keep the key in Jira, GitHub, email or the repository. Hand it over
through a protected channel only and put it into `backend/.env` on the VM
or into CI secrets.

## Smoke test after switching

```bash
curl -sS http://51.250.31.97:18080/api/assistant/v1/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"какой инкубатор есть для новорождённых?"}'
```

The response must contain:

- `answer` with Vedalina's text;
- a non-empty `sources`;
- no `handoff`, provided a published source was found.

## Similarity search (pgvector)

The schema, the indexing and the similarity search are already written and sit
alongside — details in
[vedalina_rag_pipeline.en.md](vedalina_rag_pipeline.en.md). They are switched on
by a separate pair of variables, using the same key:

```env
VEDAL_RAG_ENABLED=true
VEDAL_RAG_DOCUMENT_MODEL_URI=emb://<folder_id>/text-search-doc/latest
VEDAL_RAG_QUERY_MODEL_URI=emb://<folder_id>/text-search-query/latest
```

It can be switched on before the corpus exists: an empty index finds nothing and
the assistant answers with the previous word search. There is little point yet,
which is why it is off by default.

## MVP limits

The pipeline is complete: the text of published PDFs and DOCX files is
extracted, a document edit event triggers reindexing, and the admin area has a
button and the index state. Only one thing is missing — the corpus: for now the
index holds what the portal already shows, products, news and document cards.
The model does not read closed files: `confidential` material never enters the
index, and that level does not even exist as a value in the schema.

What remains: calibration of the proximity threshold. The 0.45 default is
deliberately provisional and can only be measured against real documents and
real questions — the procedure is described in
[Vedalina's pipeline](vedalina_rag_pipeline.en.md#how-to-calibrate-the-threshold-once-the-corpus-exists).
