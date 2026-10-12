# MuseonUrania / VEDAL Portal

[Русский](README.md) · **English**

Portfolio case: website, admin workspace with CRM, API and the AI assistant
"Vedalina" for a manufacturer of medical equipment (neonatology, intensive
care, anaesthesiology). One repository, one platform: public storefront, lead
intake and a closed contour for staff behind Keycloak. This is the public
version: no credentials, contracts, commercial terms or cloud keys — those
are handed over to the customer in a separate package outside git.

**Live site:** <https://vedal-med.ru>

## Status as of 12 October 2026

What runs:

- public site and product catalog, quote and service request forms, news,
  documents (public document listing is behind a feature switch);
- admin workspace: site content and CRM — leads, clients, deals, quotes,
  staff, audit log; staff sign in through Keycloak with roles and a second
  factor;
- Vedalina: answers only from published materials, with voice input and
  spoken replies; the model, embeddings and speech providers are switched
  by configuration;
- nightly database backups verified by restore, a liveness check every five
  minutes, deployment from GitHub Actions with reviewer approval.

What changed in October (PR #135, 8 October; stale PRs closed on 11 October):

| Before | After |
| --- | --- |
| VM in Yandex Cloud, deployment by a timer on the machine | VM in Cloud.ru, deployment over SSH from GitHub Actions (environment `production`) |
| Vedalina through YandexGPT only | three model providers: YandexGPT, GigaChat (Sber), Cloud.ru Foundation Models — `VEDAL_LLM_PROVIDER` |
| RAG embeddings from Yandex only | embeddings provider switched separately — `VEDAL_RAG_PROVIDER` (`yandex` / `gigachat` / `cloudru`) |
| speech recognition and synthesis through Yandex SpeechKit only | recognition — SpeechKit or Whisper on Cloud.ru (`VEDAL_STT_PROVIDER`); synthesis — SpeechKit or SaluteSpeech (`VEDAL_TTS_PROVIDER`) |
| one proxy per project | shared Caddy on the VM: neighbouring projects plug in through `sites.d/` files over the `edge` docker network |
| backup upload through the `minio/mc` image | upload to the bucket through `aws-cli` |

The same PR fixed dependency vulnerabilities (Next.js 16.3.6, BouncyCastle
1.86). Defaults did not change: without the new variables the stack answers
exactly as before October; choosing a provider is a deliberate change in
`.env`. Open items after the move: staff accounts in Keycloak are recreated
(there was no backup), and switching the embeddings provider requires
reindexing the knowledge base.

Latest release — [v1.0.0](https://github.com/astor-hospitality/MuseonUrania/releases/tag/v1.0.0)
(18 September 2026); the move was deployed from `main` without a new tag.

## Architecture

| Layer | Built from |
| --- | --- |
| Frontend (`frontend/`) | Next.js 16, React 19, TypeScript, CSS Modules; site and admin in one application (`app/(site)`, `app/(admin)`); Vitest + Testing Library |
| Backend (`backend/`) | Java 25, Spring Boot 4.1, Maven reactor of modules `common`, `iam`, `audit`, `catalog`, `content`, `documents`, `crm`, `gateway`, `notifications`, `assistant`, `chat`, `admin`, `app`; Spring Data JPA, Flyway, transactional outbox |
| Gateway (`backend/api-gateway/`) | separate Spring Cloud Gateway application: site and API on one origin, body limit, token check |
| Database | PostgreSQL 16 with pgvector (Vedalina's knowledge vectors); schema changes only through Flyway migrations |
| Events | Kafka 3.9 (KRaft) + Debezium Connect: outbox → topics |
| Files | S3-compatible storage through the AWS SDK: a public-read media bucket and a private documents bucket (served only through a controller with audit) |
| Auth | Keycloak 26, OIDC + PKCE, roles `portal-admin` / `portal-sales` / `portal-production`, second-factor policy |
| Assistant | module `assistant`: deterministic search over published content → with `VEDAL_ASSISTANT_ENGINE=model` the model phrases the answer on top of what was found; RAG over pgvector is enabled by `VEDAL_RAG_ENABLED`; restrictions (no diagnoses, prices, lead times, registration statuses) live in `Guardrails` before the model is called |
| Infrastructure | Docker Compose, Caddy (TLS from Let's Encrypt), systemd timers for backup and liveness, GitHub Actions (CI, CodeQL, Trivy, deployment) |

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

Three doors and no more: `Public API` `/api/public/v1/*` (published content
only, read-only), `Forms API` `/api/forms/v1/leads` (the only write from
outside), `Admin API` `/api/admin/v1/**` (Keycloak token). The assistant is
`/api/assistant/v1/*`. The site reads the catalog at build time, so a backend
outage does not take down already built pages: a live backend is needed by
the forms and by Vedalina.

## Local Run

Docker with Compose v2 is the only prerequisite. The first run builds the
images (about ten minutes); later runs take seconds.

```bash
./scripts/up.sh          # Windows: .\scripts\up.ps1
```

The script checks Docker, creates `backend/.env` with random passwords, runs
`docker compose -f backend/compose.yaml --profile app up -d --build` and
waits for the site to become ready.

| Address | Purpose |
| --- | --- |
| `http://localhost:8080` | site and API through the gateway |
| `http://localhost:8080/admin/` | admin workspace |
| `http://localhost:8080/swagger-ui.html` | Swagger UI |
| `http://localhost:8180` | local-stack Keycloak |

Stop without deleting data / recreate data from scratch:

```bash
docker compose -f backend/compose.yaml --profile app down
docker compose -f backend/compose.yaml --profile app down -v
```

Development from the host: infrastructure in containers, applications next
to it.

```bash
docker compose -f backend/compose.yaml up -d          # db, kafka, keycloak, connect
cd backend && ./mvnw spring-boot:run -pl app
cd frontend && npm install && npm run dev             # без NEXT_PUBLIC_API_URL — на локальном контенте
```

Checks (the same ones CI runs):

```bash
cd backend && ./mvnw --batch-mode verify              # тесты и порог покрытия
cd backend/api-gateway && ./mvnw --batch-mode verify
cd frontend && npx tsc --noEmit && npm run lint && npm test && npm run build
```

## Configuration

Only templates are in git; real `.env` files are never committed:

- `backend/.env.example` — local stack in Docker;
- `backend/.env.host.example` — backend on the host against containers;
- `backend/.env.cloudru.example` — production contour on the VM, every
  variable commented with where to get it;
- `frontend/.env.example` — `NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_MEDIA_URL`,
  `NEXT_PUBLIC_OIDC_ISSUER`, `NEXT_PUBLIC_OIDC_CLIENT_ID`,
  `NEXT_PUBLIC_DOCUMENTS_ENABLED`, `VEDAL_API_INTERNAL_URL`.

Key variable groups of the portal (names only, no values):

| Group | Variables |
| --- | --- |
| Database and Keycloak | `VEDAL_DB_*`, `VEDAL_RUNTIME_*`, `VEDAL_KEYCLOAK_*`, `VEDAL_OIDC_SVC_CLIENT_SECRET` |
| Domains and origins | `VEDAL_DOMAIN`, `VEDAL_PUBLIC_URL`, `VEDAL_SITE_URL`, `VEDAL_ALLOWED_ORIGINS`, `VEDAL_ADMIN_ORIGINS`, `VEDAL_ACME_EMAIL` |
| Storage | `VEDAL_S3_ENDPOINT`, `VEDAL_S3_REGION`, `VEDAL_S3_ACCESS_KEY`, `VEDAL_S3_SECRET_KEY`, `VEDAL_S3_BUCKET_MEDIA`, `VEDAL_S3_BUCKET_DOCUMENTS`, `VEDAL_MEDIA_URL` |
| Mail | `SPRING_MAIL_HOST`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `VEDAL_MAIL_FROM`, `VEDAL_NOTIFICATIONS_MANAGER` |
| Vedalina: mode | `VEDAL_ASSISTANT_ENGINE` (`deterministic` / `model`), `VEDAL_LLM_PROVIDER`, `VEDAL_LLM_TEMPERATURE`, `VEDAL_LLM_MAX_TOKENS`, `VEDAL_LLM_FALLBACK` |
| Vedalina: providers | `VEDAL_YANDEXGPT_API_KEY`, `VEDAL_YANDEXGPT_MODEL_URI`; `GIGACHAT_AUTH_KEY`, `GIGACHAT_MODEL`, `GIGACHAT_CA_BUNDLE`; `CLOUDRU_API_KEY`, `CLOUDRU_MODEL` |
| Vedalina: RAG | `VEDAL_RAG_ENABLED`, `VEDAL_RAG_PROVIDER`, `VEDAL_RAG_MAX_DISTANCE`, `VEDAL_RAG_*_MODEL_URI`, `GIGACHAT_EMBEDDINGS_*`, `CLOUDRU_EMBEDDINGS_*` |
| Vedalina: speech | `VEDAL_STT_PROVIDER`, `CLOUDRU_STT_MODEL`, `VEDAL_SPEECHKIT_API_KEY`; `VEDAL_TTS_PROVIDER`, `SALUTE_AUTH_KEY`, `SALUTE_TTS_VOICE`, `SALUTE_TTS_FORMAT` |
| Switches and operations | `VEDAL_PUBLIC_DOCUMENTS_ENABLED`, `VEDAL_PRIVACY_RETENTION*`, `VEDAL_BACKUP_*`, `VEDAL_HEALTH_*`, `VEDAL_ALERT_WEBHOOK` |

A typo in a provider name fails startup with the list of accepted values
instead of silently falling back to the default.

## How It Is Deployed

One compose project on one VM: `backend/compose.yaml` (base stack) +
`backend/compose.prod.yaml` (proxy, backup, watchdog, S3 storage mode) +
`backend/compose.cloudru.yaml` (overlay for the hosting platform's quirks).
Caddy is the only container with ports exposed: it obtains and renews
certificates itself, holds CSP/HSTS and closes `/admin` from outside.
Neighbouring projects on the same machine join the external `edge` docker
network and are described by files in `sites.d/` — no proxy of their own,
no published ports. The gateway listens on loopback only; the watchdog and
the deployment go through it.

Deployment is `.github/workflows/deploy-cloudru.yml`: a push to `main` waits
for approval in the `production` environment, then the runner runs
`scripts/deploy-prod.sh` over SSH. Rollback is `git revert` and the same
deployment. First boot of the VM is `scripts/bootstrap-cloudru.sh`, database
restore is `scripts/restore-db.sh`. At night `vedal-backup.timer` takes a
dump, verifies it by restore and uploads it to the bucket;
`vedal-health.timer` checks the portal, containers, disk and mail queue every
five minutes and alerts on state transitions. Addresses, keys and host names
live in `.env` on the machine and in GitHub secrets, not in the repository.

## Documentation Map

| Where | What |
| --- | --- |
| [docs/PROJECT.en.md](docs/PROJECT.en.md) | entry point: what the project is, repository map, the three doors, work order |
| [docs/documentation_rules.en.md](docs/documentation_rules.en.md) | every `.md` in two versions (`ru` leading, `en` mirror), glossary |
| [docs/architecture/](docs/architecture/) | owner brief, target and infrastructure architecture |
| [docs/operations/cloudru_migration_runbook.en.md](docs/operations/cloudru_migration_runbook.en.md) | move to Cloud.ru: VM bootstrap, database, DNS, Keycloak, deployment, shared proxy |
| [docs/operations/production_move_runbook.en.md](docs/operations/production_move_runbook.en.md) | variable tables, database pitfalls, acceptance table |
| [docs/operations/backups.en.md](docs/operations/backups.en.md), [monitoring.en.md](docs/operations/monitoring.en.md), [mfa_rollout.en.md](docs/operations/mfa_rollout.en.md) | backups, liveness check, second factor |
| [docs/operations/yandexgpt_activation.en.md](docs/operations/yandexgpt_activation.en.md), [gigachat_activation.en.md](docs/operations/gigachat_activation.en.md), [cloudru_models_activation.en.md](docs/operations/cloudru_models_activation.en.md), [salutespeech_activation.en.md](docs/operations/salutespeech_activation.en.md) | enabling, switching and rolling back Vedalina's providers |
| [docs/operations/vedalina_rag_pipeline.en.md](docs/operations/vedalina_rag_pipeline.en.md) | how the knowledge base is built, pgvector, reindexing |
| [docs/operations/public_feature_switches.en.md](docs/operations/public_feature_switches.en.md) | reversible switches of the public site |
| [docs/api/](docs/api/) | OpenAPI of the public and admin API, Postman collection |
| [backend/README.en.md](backend/README.en.md), [frontend/README.en.md](frontend/README.en.md) | how the backend and the site are built; every backend module has its own README |
| [docs/frontend/](docs/frontend/), [docs/strategy/](docs/strategy/), [docs/legal/](docs/legal/), [docs/products/](docs/products/) | sitemap and page briefs, business frame and Vedalina spec, compliance requirements, confirmed product data |
| [HANDOFF.en.md](HANDOFF.en.md) | frontend handover package and hard content rules |

## Maintainers

- Lead developer: architecture, backend and CMS, infrastructure and
  deployment, AI contour, integration acceptance.
- Frontend developer: UX/UI and implementation of the site and admin
  workspace in Next.js.
- Customer side: approval of structure, business wording and product facts;
  medical claims, prices and registration statuses are never invented —
  anything missing is marked `«ожидает уточнения»`.

## Public Repository Rules

Never committed: `.env`, keys, tokens, dumps, certificates, contracts, acts,
invoices, requisites, personal contacts, customer presentations and source
media, and instructions with addresses of closed services. The check is in
[docs/security/public_repo_cleanup_2026-09-08.md](docs/security/public_repo_cleanup_2026-09-08.md).
