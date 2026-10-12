# VEDAL Portal — general documentation

[Русский](PROJECT.md) · **English**

The single entry point into the project. Everything else is detail; what is
collected here is what you need in order to make a decision or start work without
re-reading thirty-nine files.

**State as of 7 September 2026.** Verified against the code of the `dev` branch
after the `back`, `front`, `infra` and `docs` layers were merged in. The numbers in
"Current state" are the result of counting across the repository, not a retelling
of documents.

What changed since the previous check (14 August): Vedalina now answers with the
YandexGPT model on top of search over published material, the conversation
streams with a "typing" indicator, a request raised from the chat creates a lead
with a human-readable number, and the answer can be rated. Staff sign-in to the
admin area works on the stand. The portal sees the visitor's real address rather
than the gateway's. The site has a crawler-facing sitemap and its own tab icon.
The database is copied every night and the copy is verified by restoring it;
the stand's health is checked every five minutes.

Tasks are tracked in GitHub — [Project "VEDAL — финальная приемка и
запуск"](https://github.com/users/michaelwelly/projects/3), not in Jira.

---

## Contents

1. [What this project is](#1-what-this-project-is)
2. [Document hierarchy](#2-document-hierarchy)
3. [Repository map](#3-repository-map)
4. [Branches](#4-branches)
5. [How it is supposed to run](#5-how-it-is-supposed-to-run)
6. [Current state](#6-current-state)
7. [What is needed on the backend side](#7-what-is-needed-on-the-backend-side)
8. [What is needed on the frontend side](#8-what-is-needed-on-the-frontend-side)
9. [Rules that must not be broken](#9-rules-that-must-not-be-broken)
10. [Products and confirmed facts](#10-products-and-confirmed-facts)
11. [Contradictions between documents](#11-contradictions-between-documents)
12. [Open questions](#12-open-questions)
13. [Order of work](#13-order-of-work)
14. [Where to find what](#14-where-to-find-what)

---

## 1. What this project is

**VEDAL Portal** is not a website but a platform: a public storefront, a CRM and a
closed contour in a single architecture. The customer is OOO "VEDAL", a
manufacturer of medical equipment for neonatology, resuscitation, anesthesiology
and intensive care. The public website `vedal-med.ru` is the entry point into
sales, not a separate storefront.

Two contours and the gateway between them:

| Contour | What is inside | Who builds it |
| --- | --- | --- |
| **Open office** | mail on the domain, calendar, Telemost, Forms, Disk without secrets, wiki/tracker | bought — Yandex 360 |
| **DMZ / Integration Gateway** | lead intake, validation of the source and attachments, antivirus/DLP, manual approve, event queue, import/export audit | written by us |
| **Closed contour** | CRM, Document Vault, PostgreSQL as the source of truth, private object storage, Keycloak + MFA, VPN / Zero Trust, logs and backups | written by us, infrastructure bought |

The principle: we do not rewrite standard services, we keep unique logic to
ourselves.

**Not exposed outside:** the customer base, commercial terms, contracts,
invoices, margins, tokens, personal data.

**Domains.** The customer named three, and they belong to different things:

| Name | What it is | Who runs it |
| --- | --- | --- |
| `vedal-med.ru` | public site and portal | us |
| `mail.vedal-med.ru` | corporate mail | bought |
| `cloud.vedal-med.ru` | corporate messenger | bought |

The portal lives on the first one only. The second and third are the open
office contour from the table above: we neither write nor deploy them, but
the portal does talk to the mail.

**A lead from the site is delivered to the corporate mail as a letter** —
the customer said so, and this is a means of delivery, not an "email as well".
The lead still stays in the portal database and in the admin area: the letter
notifies whoever works in the mailbox, it is not storage. Putting a lead into
a letter only would mean losing it together with one deleted message, and
being left without a funnel, without an owner and without an audit trail.

Technically this already works: `notifications` queues the letter in
`outbound_mail` and `MailSender` sends it. One thing is missing — the SMTP
settings: `SPRING_MAIL_HOST`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`
in `backend/.env`. The mailbox owner types the password.

**Infrastructure budget** — 50,000–80,000 ₽/month excluding development and
maintenance (Yandex 360 for 50 employees ≈ 27,450 ₽; VM, Managed PostgreSQL,
backups, object storage, logs, monitoring, Lockbox/WAF, EDI — the rest).

---

## 2. Document hierarchy

When documents disagree, seniority is as follows:

1. **The [owner brief](architecture/vedal_portal_owner_brief.en.md)** — the target
   architecture. It outranks the other documents, which is recorded in the root
   [README](../README.en.md).
2. **The [backend spec](superpowers/specs/2026-08-06-vedal-portal-architecture-design.en.md)** —
   how the brief is achieved technically. Eight implementation steps, decisions
   accepted and rejected.
3. **[backend/README](../backend/README.en.md)** and 11 module READMEs — what has
   already been built and what it stands on.
4. **[docs/products/README](products/README.en.md)** — the only product data
   confirmed by the customer (datasheets from 5 August).
5. Everything else is the frame and the requirements written before the technical
   decisions.

Three generations of documents, which explains the variance in language and
detail:

| Generation | When | Language | Files |
| --- | --- | --- | --- |
| Business frame | July 2026 | English | [project_brief](strategy/project_brief.en.md), [functional_requirements](strategy/functional_requirements.en.md), [roadmap](operations/roadmap.en.md), [infrastructure_architecture](architecture/infrastructure_architecture.en.md), [content_and_seo_plan](strategy/content_and_seo_plan.en.md), [competitor_notes](strategy/competitor_notes.en.md), [team_estimate](operations/team_estimate_7_people.en.md) |
| Frontend package | July 2026 | English | [sitemap](frontend/sitemap.en.md), [content_model](frontend/content_model.en.md), [page_briefs](frontend/page_briefs.en.md), [implementation_checklist](frontend/implementation_checklist.en.md), [frontend_design_handoff](strategy/frontend_design_handoff.en.md), [frontend_variants](strategy/frontend_variants.en.md), [vedalina_assistant_spec](strategy/vedalina_assistant_spec.en.md) |
| Technical truth | 6–11 August 2026 | Russian | [owner brief](architecture/vedal_portal_owner_brief.en.md), [spec](superpowers/specs/2026-08-06-vedal-portal-architecture-design.en.md), [backend/README](../backend/README.en.md) and the module ones, [catalog plan](superpowers/plans/2026-08-06-catalog-module.en.md) |

A measurement across 39 files: 18 were written 70–98% in Russian, 20 were 75–100%
English. There is almost no mixing inside a single file — this is a split between
generations. Bringing everything to a bilingual form (`ru`/`en` with a switcher
inside the file) is a separate task.

---

## 3. Repository map

```
MuseonVedalina/
├─ docs/PROJECT.md          ← this file, the entry point
├─ frontend/                Next.js 16, App Router, TypeScript, CSS Modules
│  ├─ app/(site)/           the public site: nine routes
│  ├─ app/(admin)/          the admin UI: dashboard, products, categories, news,
│  │                        documents, audit log; CRM — leads, clients, deals,
│  │                        quotes, analytics. Its own root layout — the site's
│  │                        chrome does not leak into it
│  ├─ components/           Header, Footer, LeadForm, VedalinaChat/Widget, AnimatedLogo, VedalMap…
│  ├─ content/*.ts          ALL page text; unconfirmed facts marked «ожидает уточнения»
│  ├─ lib/api.ts            public API: read at build time
│  ├─ lib/admin.ts          admin API: browser only, token only
│  ├─ lib/auth.ts           Keycloak sign-in, authorization code with PKCE
│  └─ Dockerfile            pages are built when the container starts, not the image
├─ backend/                 a 12-module Maven reactor + a separate gateway, one database
│  ├─ pom.xml               the reactor root: the module list and shared versions
│  ├─ <module>/pom.xml      ← each has its own pom: dependencies declared and checked
│  ├─ <module>/src/main/java/ru/vedal/portal/<module>/  ← the module's code
│  ├─ <module>/README.md    ← purpose, boundaries, what it depends on
│  ├─ app/                  the only module that becomes an application: entry point,
│  │                        configuration, the Flyway schema, application tests
│  ├─ app/src/main/resources/db/migration/   ← Flyway, the single source of the schema
│  ├─ api-gateway/          a separate application outside the reactor: the single entry point
│  ├─ keycloak/             the local stack's realm + a README on the two addresses
│  ├─ debezium/             the outbox → Kafka connector + the settings walkthrough
│  ├─ tools/seed-catalog.mjs   generator for the V2 migration from frontend/content/products.ts
│  ├─ Dockerfile            the portal's image
│  └─ compose.yaml          the whole stack: PostgreSQL 16 with wal_level=logical,
│                           Kafka 3.9 in KRaft, Keycloak, Kafka Connect;
│                           profile `app` adds the portal, the gateway and the site
├─ docs/
│  ├─ api/                  OpenAPI export for the public API (assembled from the code)
│  ├─ architecture/         owner brief, infrastructure
│  ├─ strategy/             business frame, requirements, Vedalina, SEO, competitors
│  ├─ frontend/             sitemap, content models, page briefs, checklist
│  ├─ legal/                compliance requirements: personal data, hosting, claims
│  ├─ operations/           roadmap, autodeploy, vedal-med.ru domain cutover
│  ├─ products/             public summary for VEDAL R1/R2, A-2000, Т-100
│  ├─ security/             public repository cleanup checks
│  └─ superpowers/          the backend spec, the catalog module plan
├─ assets/vedalina/           Vedalina avatars; MVP — vedalina-avatar-middle-v1.png
└─ assets/brand/              public brand assets
```

Presentations, contracts, acts, credentials, commercial calculations and
customer source materials are stored outside public git in the official project
package.

**Important about the backend layout.** The folders `backend/crm/`,
`backend/iam/` and the rest are **real Maven modules**: their own `pom.xml`,
their own `src/`, their own README. Until 14 August 2026 they merely documented
boundaries while the code sat in one heap under `backend/src/`; the boundaries
rested on attentiveness and the build knew nothing about them.

It knows now. A module reaching into a neighbour's guts fails compilation — not
review, but `mvnw`. The formula is the one the client asked for: "like
microservices, but like a monolith". The boundary lives in the build, the
process stays single, and the transactional outbox is intact: the entity row and
the event row commit in one `COMMIT`, because the database and the transaction
are shared.

Eleven modules plus `app`, which assembles them:

| Module | Responsibility |
| --- | --- |
| [app](../backend/app/README.en.md) | Spring Boot assembly, configuration, entry point |
| [common](../backend/common/README.en.md) | shared types, errors, validation, outbox, rate limits |
| [iam](../backend/iam/README.en.md) | accounts, roles, access, the staff directory |
| [catalog](../backend/catalog/README.en.md) | products and categories, public API |
| [content](../backend/content/README.en.md) | news and press centre |
| [documents](../backend/documents/README.en.md) | documents, publication statuses, object storage |
| [gateway](../backend/gateway/README.en.md) | lead intake: validation, approve, handover to CRM |
| [crm](../backend/crm/README.en.md) | leads, clients, deals, quotes, correspondence history, funnel analytics |
| [notifications](../backend/notifications/README.en.md) | customer letters and manager notifications |
| [assistant](../backend/assistant/README.en.md) | Vedalina: answers from published content, limits |
| [audit](../backend/audit/README.en.md) | log of actions and document access |

Deferred, with no folders yet: **knowledge** (internal AI search, stage 3 of the
roadmap) and **vlm** (visual models for service and manufacturing, a separate
pilot).

**Dependency rule:** a module never imports another module directly, only its
interface. `crm` knows `CatalogQuery`, not the internals of `catalog`. This is the
price for being able to extract a module into a separate service without
untangling a knot.

Half of that rule is now checked by the build: what is absent from a module's
`pom.xml` is invisible to it. The other half — "only the interface" — still rests
on people: `gateway` declares a dependency on the whole of `crm` and could reach
`LeadRepository`. Separating that fully would mean splitting every module into
`-api` and an implementation; deliberately not done yet — twenty-two artifacts
instead of twelve for a rule nobody has broken.

The dependency graph is a DAG without a single cycle:

```
common, iam → (никого)
audit → common
catalog, content, documents → common, audit
crm → common, audit, documents
gateway, notifications → common, crm
assistant → common, audit, catalog, content, documents
admin → common, audit, catalog, content, crm, documents, iam
app → всё вышеперечисленное
```

---

## 4. Branches

| Branch | Purpose |
| --- | --- |
| `main` | released, finished work only |
| `dev` | integration, everything is reviewed here |
| `front` | frontend, merges into `dev` |
| `back` | backend, merges into `dev` |
| `docs` | documentation, merges into `dev` |

The layered model is enforced by the `gitflow` skill — consult it before any git
operation rather than guessing from general practice.

**A known problem as of 12 August 2026:** `main` is 55 commits behind `dev`, so
`main` contains **zero Java files** — an outside observer sees a project made of
frontend and documents with no code. Meanwhile the local `front` is 39 commits
ahead of `origin/front`. The cure is merging `dev` → `main` and pushing `front`.

---

## 5. How it is supposed to run

### 5.1 Three doors and no more

We do not add a fourth: a new feature arrives into one of the three. Then the
perimeter is checked in three places rather than in thirty controllers.

| Door | Who calls it | Property |
| --- | --- | --- |
| `Public API` `/api/public/v1/*` | site build, Vedalina | read-only, published content only, cacheable |
| `Forms API` `/api/forms/v1/leads` | website forms, Yandex Form, mail parsing | **the single external write**, idempotency by `Idempotency-Key` |
| `Admin API` `/api/admin/v1/**` | employee | JSON, Keycloak token, roles `portal-admin`, `portal-sales` and `portal-production` — by contour, see 12.3. The door is single, so restricting it at the proxy takes one rule — **but no such rule is in the [Caddyfile](../backend/proxy/Caddyfile) today**, and it is open to the internet |

The employee door changed shape rather than being created anew: instead of
server-rendered Thymeleaf pages it is JSON, with the Next.js admin UI on top.
The pages are gone, and with them the portal has **no browser-facing page at
all** — which means no login form, no cookie session, and no CSRF token to
protect. That removed a whole class of risk rather than shortening the code: the
ambient authority cross-site request forgery relies on cannot exist when there is
no session. `AdminAccessTest` guards that state — a controller with a browser
page coming back will fail the build.

Errors from every door are `application/problem+json` (RFC 9457), one format for
everything. The forms and the assistant have per-client rate limits.

**A gateway stands in front of the doors.** `backend/api-gateway/` is a separate
Spring Cloud Gateway application, the single entry point `http://localhost:8080`.
The main gain is not routing: the site and the API end up on one origin, and CORS
drops out of the perimeter entirely — a browser does not make a cross-origin
request when the domain is the same. The body limit and token verification also
move into one place.

It does not, however, provide trust: the portal verifies the token itself and
refuses a request that arrived bypassing the gateway. The gateway is a filter,
not a trust boundary.

The public documents door is a temporary exception within the Public API. With
`VEDAL_PUBLIC_DOCUMENTS_ENABLED=false`, both the listing and file routes return
`404`; the switch is checked before rate limiting. The admin door and stored
materials are not deleted. The variable must reach both `portal` and `site`;
the complete mapping is in the
[reversible switch table](operations/public_feature_switches.en.md).

### 5.2 The path of a lead

It also defines the module boundaries.

```
Lead (website, form, mail)
  → gateway         checks the source, the fields and the attachments
  → crm             creates a draft lead without access to closed data
  → crm             deal, quote, documents, statuses inside the portal
  → notifications   only a templated letter goes outside
```

Every step writes to `audit`.

### 5.3 Five properties we measure stability by

1. **A backend outage does not take the website down.** The catalog, the news and
   the documents are baked into static files at build time. A live backend is
   needed only by the forms and by Vedalina.
2. **The schema changes only through migrations.** A manual `ALTER` in production
   is forbidden: after it a dump restores into a schema that is not in the
   history.
3. **Only published content goes outside.** This is a property of the query, not
   of the editor's discipline.
4. **A lead is never lost.** The database write and the event are committed in one
   transaction.
5. **One door for external writes.** The validation rule is written once.

### 5.4 Four ports to the outside

The domains never look behind a port; changing the implementation is a matter of
configuration.

| Port | Now | Later |
| --- | --- | --- |
| `EventPublisher` | log, Kafka or Debezium — via `vedal.events.publisher` | Managed Kafka |
| `MailSender` | writes to the log | Yandex 360 SMTP |
| `FileStorage` | local directory or S3 — via `vedal.storage.kind`; a directory in the stack by default, the real Object Storage once keys are set | Yandex Object Storage |
| `LlmEngine` | YandexGPT over the portal search: by words, and with `VEDAL_RAG_ENABLED=true` by proximity in pgvector | PDF indexing once the corpus arrives |

### Buckets are created by hand, once

The `minio-init` container used to do this on every stack start. With MinIO
gone the step moved here, and that is not a loss of automation: a bucket in
the cloud is not something recreated on a schedule, and its policy is not
something rewritten past the cloud's own audit log.

Two buckets, and that is a requirement rather than a layout:

```bash
yc storage bucket create --name vedal-documents
yc storage bucket create --name vedal-media --public-read
```

`vedal-documents` stays fully closed: the file is served by a controller that
checks publication and records the access in the audit log. Direct bucket
access would hand out a closed document past both the check and the log.

`vedal-media` is open for reading an object and **not** for listing: product
photos are visible on the site anyway, a listing of everything in the bucket
is not. If the provider offers no `--public-read`, set the same policy
explicitly:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "AWS": ["*"] },
      "Action": ["s3:GetObject"],
      "Resource": ["arn:aws:s3:::vedal-media/*"]
    }
  ]
}
```

Keys come from a service account: **Service accounts → Create new key →
Static access key**, with `storage.editor` on both buckets.

### The stack still comes up without keys

`vedal.storage.kind=local` by default: files land in a directory inside the
container. The site, the admin panel, the database and the events all work in
full — keys are only needed by whoever works on media or documents.

It was the other way round at first: once MinIO was gone the keys became
mandatory, and the stack stopped coming up for anyone without cloud access.
The requirement was right in substance and wrong in reach — it also forbade
running everything that has nothing to do with storage.

A directory on disk does not repeat MinIO's mistake. What was dangerous was
not the second instance as such but its RESEMBLANCE to the cloud: the same S3,
the same bucket names, the same client — so a file put in the wrong place
looked like a file put in the right one. A directory resembles nothing, and
cannot be mistaken for a bucket.

The price is known and worth knowing: a photo uploaded by an editor through
the admin panel will not appear on the site. The site reads media from the
bucket address, and the directory is not served outward — by design, see
`LocalFileStorage`.

Setting `VEDAL_STORAGE_KIND=s3` and leaving the keys empty will not work:
`S3Config` stops the start and names the missing variables. The check lives in
the application rather than in `compose.yaml`, and deliberately in one place:
written in two, it would drift apart sooner or later, and compose would forbid
starting what the application brings up perfectly well.

In a deployed environment the mode is pinned separately — `VEDAL_STORAGE_KIND:
s3` in `compose.prod.yaml`. Had production inherited the local default,
documents would land in a container volume and vanish on the very next deploy,
and there would be no failure at all: the application writes to a directory
perfectly well and reports "file saved".

Photos from the repository are uploaded by a separate command, not on their
own:

```bash
docker compose --profile seed run --rm media-seed
```

The profile is not tidiness either. While a local MinIO stood next to it, the
upload on every stack start wrote into a local bucket. Against the real Object
Storage the same run writes where the live site reads from — for everyone who
brings the stack up locally. When working on media, create your own
`vedal-media-dev` and point `VEDAL_S3_BUCKET_MEDIA` at it.

Privacy is a property of `FileStorage`, not of the calling code, and it is
anchored to the storage area: `DOCUMENTS` lives in a closed bucket and is served
only through the controller that checks publication and records the access in the
audit log, while `MEDIA` lives in a read-open one and is served bypassing the
application. Separating them by buckets rather than folders is mandatory: an S3
access policy is set per bucket, and a private folder inside a public bucket is a
public folder.

Signed links are deliberately not used: an issued link lives until it expires,
survives the document being unpublished, and access through it does not reach the
audit log.

The file limit is 20 MB. The real check sits in multipart parsing, that is,
before the body reaches the heap; the check in the storage is a second line. The
same number is aligned with the gateway's body limit and with
`client_max_body_size` on the proxy: a smaller limit further up the chain drops
the upload with a 413 before the application, and the editor sees the proxy's
page instead of a clear refusal.

**The employee portrait never went into a bucket at all.** `vedal-media` is
anonymously readable — that is its purpose — and an employee's face placed there
would be published; on top of that the service key holds no rights on it
(issue #37). `vedal-documents` is closed and reachable, but it is the document
vault with its own publication rules and its own door. The portrait lives in the
database, table `staff_avatar`: one row per login, a normalised 256×256 JPEG,
tens of kilobytes per person — and together with the database it lands in the
nightly backup, which buckets do not have at all. Its limit is its own, 2 MB:
twenty megabytes per person is a file store, not a column. The reasoning lives in
migration `V35__staff_avatar.sql` and in
[backend/iam/README](../backend/iam/README.en.md).

### 5.5 Events

A transactional outbox: the entity row and the event row are committed by a single
`COMMIT`. Publishing directly from a handler is forbidden — between the `INSERT`
and the send there is a gap that leads fall into.

Who reads the outbox is a matter of the `vedal.events.publisher` setting:

| Value | Publishing | Delivery to consumers |
| --- | --- | --- |
| `log` | the event goes to the log | in process |
| `kafka` | the relay polls the table and publishes itself | in process |
| `debezium` | a connector reads the PostgreSQL write-ahead log | from the topics |

The stack runs `debezium`. Nobody polls the table: events leave right after
`COMMIT`, latency stops depending on the poll interval, and there are no idle
queries against the database at all. It requires `wal_level=logical` and a
running Kafka Connect — the settings walkthrough is in
[backend/debezium/README.en.md](../backend/debezium/README.en.md).

In this mode `published_at` is stamped by the **consumer**, not by the relay.
That changes the meaning of the primary monitoring signal for the better: the lag
measures the whole path `COMMIT` → log → connector → topic → consumer, rather
than what the application managed to put on the queue.

| Topic | Who writes | Who reads |
| --- | --- | --- |
| `vedal.leads.v1` | `crm` | letters, audit |
| `vedal.deals.v1` | `crm` | no consumer yet, audit |
| `vedal.documents.v1` | `documents` | indexing, audit |
| `vedal.notifications.v1` | `notifications` | sending |
| `vedal.audit.v1` | everyone | writing the log |

Deals and quotes were given their own topic rather than mixed into leads: they
have a different life cycle and different consumers — a lead produces a letter
to the client, while a deal lives for weeks and matters to reporting.
`vedal.deals.v1` has no consumer yet: the letter carrying the quote itself
depends on Яндекс 360 SMTP.

The partitioning key is the entity identifier: events of one lead do not get
reordered. Consumers are idempotent. Anything still unprocessed after retries goes
to the DLQ and is handled manually, otherwise one broken PDF stops the conveyor.
Kafka is not the source of truth and is not backed up: everything in the topics
originates from the outbox.

### 5.6 Data

**One database.** Separate databases are not part of the architecture — this
follows directly from the transactional outbox. Separate databases exist in one
sense only: one per environment.

Key tables:

```
category          id, slug, name, position
product           id, slug, name, kind, summary, detail, doc_status, published,
                  sort_order, image_src, image_alt, created_at, updated_at
product_category  product_id, category_id
product_spec      id, product_id, kind, position, label, value, muted
outbox            id, aggregate, aggregate_id, type, payload, created_at, published_at
audit_entry       id, at, actor, action, subject, subject_id, correlation_id, ip, payload
staff_avatar      login, subject, content_type, bytes, width, height, etag, updated_at
lead              id, form, name, company, phone, email, product_slug, message,
                  consent_version, consent_at, source, language, campaign,
                  status, owner, correlation_id, idempotency_key, created_at
client            id, name, kind, inn, kpp, external_id, country, city,
                  email, phone, note, owner, version, created_at, updated_at
deal              id, client_id, lead_id, pipeline, title, stage, amount,
                  currency, product_slug, owner, closed_at, lost_reason,
                  version, created_at, updated_at
quote             id, deal_id, number, status, currency, valid_until, note,
                  total, sent_at, decided_at, version, created_at, updated_at
quote_item        id, quote_id, position, product_slug, name, quantity,
                  unit_price, amount
interaction       id, deal_id, client_id, lead_id, kind, direction, at,
                  subject, body, actor, created_at
deal_document     deal_id, document_id, attached_by, attached_at
knowledge_source  id, kind, external_id, title, url, visibility, language,
                  checksum, indexed_at
knowledge_chunk   id, source_id, position, text, model, embedding, created_at
```

`language` and `campaign` on a lead are not decoration: without them two of the
four analytics dimensions ("by language", "by campaign") cannot be computed at
all. They live on the lead rather than the deal because they are a property of
where the person came from, not of how they were worked with afterwards.

Three pipelines share one `deal` table with different sets of stages rather
than three tables: they share the card, the owner, the history and the
analytics. The set of stages is enforced by the `deal_stage_check` constraint.

`interaction` is the only CRM table without a `version` column, and that is a
decision: history is append-only. A correspondence entry that can be corrected
after the fact stops being history exactly when it is needed.

`doc_status` (`confirmed | pending`) and `published` are **different** flags. The
first means "the specifications are confirmed by a datasheet" and draws a badge on
the site, the second means "visible outside". Collapsing them into one removes half
the catalog from the site.

The catalog is filled by the `V2__catalog_seed.sql` migration, which is
**generated** from `frontend/content/products.ts` rather than written by hand:

```bash
node backend/tools/seed-catalog.mjs > backend/app/src/main/resources/db/migration/V2__catalog_seed.sql
```

Editing `V2` by hand is pointless — regenerate and commit it again.

### 5.7 What is enforced by database constraints rather than code

Rules that cannot be bypassed by editing a controller or by an editor's mistake:

- `document_public_only` — an internal or confidential document cannot become publicly downloadable;
- `document_published_has_file` — publishing without an uploaded file is forbidden;
- `document_published_is_listed` — a published document must appear in the listing;
- `lead_idempotency_key_idx` — resubmitting a form does not create a second lead;
- `event_consumed_idx` — a redelivered event does not produce a second letter;
- `news_published_needs_date` — a published news item must have a date;
- `deal_stage_check` — a deal's stage must belong to its own pipeline;
- `deal_lead_idx` — a lead is converted into a deal exactly once;
- `client_inn_idx` — a second card with the same ИНН would split one
  organisation's history across two places;
- `quote_sent_has_date` — a sent quote must remember when it was sent;
- `interaction_has_subject` — a history entry is attached to a deal, a client
  or a lead rather than hanging in the air;
- the `audit_entry_append_only` trigger — the log can be neither edited
  retroactively, nor deleted row by row, nor emptied wholesale;
- the `version` column on products, news items, documents, clients, deals and
  quotes — two editors who opened the same card do not silently overwrite each
  other.

One constraint is **deferred** — `quote_item_position_unique`
(`deferrable initially deferred`). That is not a relaxation: quote lines are
replaced wholesale, and in a single flush Hibernate inserts the new row before
deleting the old one. Checking at `COMMIT` keeps the rule strict and removes
the dependency on statement order inside the transaction.

The version is checked twice, and that is not belt-and-braces. The explicit check
in the domain catches the common case: the card was opened in the morning and
saved in the evening, and somebody else edited it in between. `@Version` catches
a genuine concurrent write by two transactions — without the explicit check it
would never fire, because the version is read from the database inside the same
transaction and always matches itself. Both cases surface as a `409` with an
explanation: for the editor it is one and the same situation.

**What used to be written here about `TRUNCATE` was wrong.** The belief was that
a trigger does not help because statement triggers do not fire on `TRUNCATE`.
They do: PostgreSQL supports `BEFORE TRUNCATE ... FOR EACH STATEMENT`, and such
a trigger stops the statement. Verified on PostgreSQL 16 and pinned by the test
`AuditLogTest.journalRejectsTruncate`.

The `V15__least_privilege.sql` migration puts both measures in place, and they
close different things:

| Measure | What it holds against | What limits it |
| --- | --- | --- |
| the `audit_entry_no_truncate` trigger | a typo in a console, an injection into a query, an extra line in a script | bypassed by an explicit `ALTER TABLE ... DISABLE TRIGGER` |
| revoking `UPDATE`/`DELETE`/`TRUNCATE` from the application role | the same things, at the level of privileges rather than one table | **does not bind a superuser at all** |

The second limitation is not a detail but a deployment requirement. The
`postgres` image creates `POSTGRES_USER` as a superuser, and a superuser does not
fail a privilege check — it does not undergo one. In the local stack and in the
tests the revoke is therefore decorative, and the log is held by the trigger
alone. **In a deployed environment the application role must be an ordinary
one** — otherwise everything above is written in vain.

`TRUNCATE` is revoked not only on the log but on every table: the application
never empties any of them wholesale. The revoke covers the tables that existed
at `V15`; a table from the next migration comes with full owner rights again, and
`ALTER DEFAULT PRIVILEGES` does not change that — an owner's rights are implied,
not granted. So a `revoke` line has to be added to every migration that creates
a table, and `LeastPrivilegeTest` makes forgetting it impossible: otherwise the
difference between "`TRUNCATE` is revoked" and "revoked except for three tables
added in September" would be visible from nowhere.

Left open: the application connects to the database as the schema owner, that is,
as the same role Flyway runs migrations with. An owner grants revoked rights back
to itself in one line. Real separation is a dedicated runtime role, neither a
superuser nor the owner; it touches `compose.yaml`, `compose.prod.yaml`,
`.env.example` and `PostgresTestBase`.

### 5.8 Environments and running it

Environments: `prod`, `staging`, `internal`, `lab`. In `lab` — non-secret data
only. In deployed profiles the connection is configured **only** through
environment variables, with no development defaults: a production instance that
silently came up on the development database is worse than one that did not come
up at all. The check runs before the datasource is created and names the missing
variables one by one.

JDK 25 and Docker are required; Maven does not have to be installed, the wrapper
is in the repository.

**On a clean machine — one command.** Docker is all you need: the script checks
it, generates `backend/.env` with random passwords, builds the images, waits for
readiness and prints the addresses and the account. The first run takes about ten
minutes, later ones take seconds.

```bash
./scripts/up.sh
```

On Windows without bash — the same thing:

```
.\scripts\up.ps1
```

Underneath it is the same compose command, which can also be called directly:

```bash
docker compose -f backend/compose.yaml --profile app up -d --build
```

| Address | What |
| --- | --- |
| `http://localhost:8080` | site, API, admin UI at `/admin/` — all through the gateway |
| `http://localhost:8080/swagger-ui.html` | the specification, both groups |
| `http://localhost:8180` | Keycloak, console `admin` / `admin-local` |
| `http://localhost:8083` | Kafka Connect |
| `http://localhost:8081` | the portal directly, bypassing the gateway |

The local stack's editor account is `editor` / `editor-local`, created by the
realm import. Details, and why the password sits in the repository, are in
[backend/keycloak/README.en.md](../backend/keycloak/README.en.md).

**Environment only, applications from the machine.** This way they are visible in
a debugger and an edit does not require rebuilding an image:

```bash
docker compose -f backend/compose.yaml up -d
```

```bash
cd backend && ./mvnw spring-boot:run
```

```bash
cd frontend && npm run dev
```

The portal comes up on `http://localhost:8081` (not 8080 — that port belongs to
the gateway). The schema is owned by Flyway alone, migrations are applied on
start. The first administrator of the local mode is created only if both
`VEDAL_ADMIN_USER` and `VEDAL_ADMIN_PASSWORD` are set, otherwise the account
simply does not exist; with `vedal.iam.mode=keycloak` accounts live in Keycloak.

**The site in a container, but with edits visible at once.** The ordinary `site`
service builds its pages at container start from what was baked into the image:
showing a colour change means rebuilding the image, and until then port `3000`
serves yesterday's code. The `compose.dev.yaml` overlay runs the site in
development mode with the `frontend` directory bind-mounted — an edit shows up
immediately, while the address and the network stay the same:

```bash
docker compose -f backend/compose.yaml -f backend/compose.dev.yaml --profile app up -d site
```

Back to the ordinary mode:

```bash
docker compose -f backend/compose.yaml --profile app up -d --build site
```

Three pitfalls that shape the overlay:

- **Anonymous volumes on `node_modules` and `.next` are mandatory.** Otherwise
  the host's modules, built for Windows, shadow the Linux ones from the image.
- **`.next` is created in the image owned by `node`.** Docker creates an empty
  volume as root when the image has no such directory, and the run under `node`
  fails on `mkdir /app/.next/dev` with `EACCES`.
- **Development mode runs on webpack, not Turbopack.** Filesystem events do not
  cross a Windows bind mount into the container; polling fixes that, but
  `WATCHPACK_POLLING` is understood by webpack and not by Turbopack, so the
  container keeps serving the old page while the file inside is already new.

**This does not replace the check before handing work over.** Development mode
builds pages on request, the ordinary mode builds them ahead, and differences
between the two surface only in the ordinary one. Show a result as finished from
the plain stack, without the overlay.

The tests run against a real PostgreSQL through Testcontainers and **require a
running Docker**. H2 is not used: dialect differences should surface here, not in
production.

```bash
cd backend && ./mvnw test
```

### 5.8a A deployed environment

The `compose.prod.yaml` overlay on top of the regular file:

```bash
docker compose -f backend/compose.yaml -f backend/compose.prod.yaml \
  --profile app up -d --build
```

What it changes and why:

- **No default value for any secret.** `${VAR:?...}` fails the start naming the
  missing variable. A production instance that silently came up with the password
  "vedal" is worse than one that did not come up.
- **Only the reverse proxy is exposed.** The ports of the database, broker,
  storage, Keycloak, Connect and the applications themselves are not published:
  inside the docker network they reach each other anyway, and from outside every
  open port is a door someone has to guard.
- **TLS on Caddy.** It obtains and renews certificates itself — the only reason
  it is here rather than nginx, where the same work is done by certbot and a
  timer, that is, by two more places that break silently and surface ninety days
  later.
- **Media on a separate domain**, read-only, `sandbox`, and no execution of
  the content. Editors put files there, and sharing the site's origin and cookies
  with them serves nothing.
- **Keycloak on a persistent database** and in `start` mode rather than
  `start-dev`: the embedded H2 does not survive a container restart, and the
  employee accounts go with it.
- **Backups** — a daily `pg_dump -Fc` with weekly rotation. This is not the
  target scheme from the spec (continuous WAL via `wal-g` into another
  account's bucket) but a minimum that beats having none. Once a month it must be
  restored into a separate database and the row counts compared: a backup never
  restored is a hypothesis.

### 5.9 Perimeter, observability, backups

The application sits behind a reverse proxy. Without
`server.forward-headers-strategy=framework` and a list of trusted proxies the rate
limit counts all visitors as one client and the audit log records the proxy
address instead of the user's. The body size limit on the proxy is aligned with
document uploads, otherwise a PDF fails with 413 before reaching the application.

A VPN is a perimeter, not authorization. Sign-in, roles and audit in the admin
panel are needed regardless of whether it is closed off at the network level.

**The main monitoring signal is the outbox lag.** The relay stopped: the
application is green, forms are accepted, users see "thank you", leads pile up and
go nowhere. The alert is records older than 5 minutes. The second signal: the last
successful backup is older than a day. Personal data is not written to the logs,
only the `correlation_id` and the lead identifier — otherwise the logging system
also becomes a store of personal data.

#### The watchdog

A first step is in place: the stack has a
[watchdog](../backend/watchdog/README.en.md) — a container that checks the
services, memory and disk once a minute and sends mail on a change of state.
It appeared after a week the stand spent falling over and coming back: health
checks on the services existed before it, but only `docker` sees them, only on
that same machine, and only if somebody asks.

The record turned out to matter more than the alarms. Every check goes to the
log as one line with memory and disk, and that is exactly the answer to "what
was happening at 03:40" that was missing when the stand started to fall.

**What the watchdog does not close — two gaps, both worth knowing.**

First: it lives on the machine it watches. If memory runs out, the system kills
it too; if the machine goes down, the silence is total and indistinguishable
from the silence of everything being fine. Silence from the watchdog is not
proof that anything works. Only an observer on the outside can notice the death
of the machine itself.

Second: **the watchdog does not check the outbox lag** — the very main signal
named in the paragraph above. The `vedal.outbox.lag.seconds` metric is computed
(`OutboxRelay.measure`, in `debezium` mode too) and, past five minutes, written
as a warning to the portal's log. But actuator exposes only `health` outward and
without details — the door that would show it does not exist. Making one takes
more than adding an indicator: landing in the overall health, it would fail the
container's health check, and docker would start restarting the portal because
leads are piling up. It needs a separate health group, and that is backend work,
not watchdog work.

Why not Prometheus and Grafana: they pay for themselves on a fleet of machines.
Here there is one machine, and it is already short on memory — short enough that
memory is suspect number one in the crashes. Putting another gigabyte of
observer next to it is curing hunger with another eater. Same reason there is no
Kubernetes in this project.

The target backup scheme: continuous WAL through `wal-g` into object storage, a
full backup once a week, an encrypted `pg_dump -Fc` into a bucket of another
account, bucket versioning, a service account with write-only permissions. A
monthly check: the latest dump is restored into a separate database, the
migrations are run and the row counts are compared. A backup that has never been
restored is a hypothesis.

### 5.10 Personal data

The forms collect a name, a phone number and an email. Consequences: store not a
checkbox but the **version of the consent text** and the time (a year later there
is no other way to prove what exactly the person agreed to); a retention period
and automatic cleanup (preliminarily 3 years, awaiting confirmation); deletion
upon request; infrastructure in Russia.

---

## 6. Current state

### 6.1 Backend — working

166 Java files in the portal and 3 in the gateway, 56 test classes and 364 tests
on the backend, 353 frontend tests across 33 files — all green. 31 Flyway
migrations (the latest, V31, is the answer rating), 23 controllers, 13 catalog
items in the seed, 5 categories. The coverage gate is 70% of instructions and
45% of branches.
Spring Boot 4.1.0 on Spring Framework 7, Java 25, Jackson 3,
PostgreSQL 16, Testcontainers. The gateway runs Spring Boot 4.0.8 with Spring
Cloud Gateway 5.0.2: that is the only supported pair, and the version skew is
harmless precisely because it is a separate process.

All implementation steps but the last are closed: `catalog` → `content` →
`crm` + Forms API → `notifications` → `documents` → Kafka (publishing **and**
consuming from the topics via Debezium) → `assistant`. Step 8 — the move to the
cloud — remains.

Working routes:

| Route | Who calls it | What it does |
| --- | --- | --- |
| `GET /actuator/health` | monitoring | liveness; the other endpoints are closed |
| `GET /api/public/v1/categories` | site build | catalog categories |
| `GET /api/public/v1/products` | site build | published items only, `Cache-Control: max-age=300` |
| `GET /api/public/v1/products/{slug}` | site build | product page; unpublished returns 404 |
| `GET /api/public/v1/news` | site build | the feed; empty is `[]`, not an error |
| `GET /api/public/v1/news/{slug}` | site build | a publication; unpublished returns 404 |
| `GET /api/public/v1/documents` | site build | when enabled, the published listing; when disabled, 404 |
| `GET /api/public/v1/documents/{slug}/file` | visitor | when enabled, a published file; a disabled section, closed document, or missing file returns 404 |
| `POST /api/forms/v1/leads` | website forms | lead intake, `Idempotency-Key`, `202` response |
| `POST /api/assistant/v1/ask` | Vedalina | an answer from published content with links |
| `GET /api/assistant/v1/prompts` | chat widget | quick-reply buttons and what each one does |
| `POST /api/assistant/v1/chat` | chat widget | a visitor message; `intent` is the button pressed |
| `POST /api/assistant/v1/chat/handoff` | chat widget | call a specialist: the conversation joins the queue |
| `/api/admin/v1/products`, `/categories` | admin UI | the whole catalog: editing, specs, images, publishing |
| `/api/admin/v1/news` | admin UI | news: creating, editing, publishing, deleting a draft |
| `/api/admin/v1/documents` | admin UI | the card, file upload up to 20 MB, publication with the refusal explained |
| `/api/admin/v1/leads` | admin UI | leads by page, status and owner; conversion into a deal |
| `/api/admin/v1/clients` | admin UI | client base: search, card, editing, history |
| `/api/admin/v1/deals` | admin UI | deals of three pipelines: stages, attachments, quotes, history; filtering by pipeline, stage, client and owner |
| `/api/admin/v1/quotes` | admin UI | quotes: lines, total, sending, the client's decision |
| `/api/admin/v1/analytics` | admin UI | the funnel by product, source, language and campaign |
| `/api/admin/v1/audit` | admin UI | the log with filters and the chain by `correlation_id` |
| `/api/admin/v1/media` | admin UI | image upload into the read-open bucket |
| `/api/admin/v1/session` | admin UI | who signed in and which roles the portal parsed |
| `/api/admin/v1/staff` | admin UI | employees to pick an owner from; read only |
| `/api/admin/v1/staff/{login}/avatar` | admin UI | an employee portrait; no portrait means 404, and the circle keeps its letter |
| `/api/admin/v1/profile/avatar` | admin UI | your own portrait: set, replace, remove. No login in the path — the token decides whose it is |
| `/api/admin/v1/chats` | admin UI | conversations and the queue of those waiting; the list filters by owner |
| `/api/admin/v1/duty` | admin UI | the on-call schedule: who is on the line today, assigning a day, handing over a shift |

The sixty-three routes and eighty-two operations of the admin API are described by
a separate specification group —
[docs/api/vedal-admin-openapi.yaml](api/vedal-admin-openapi.yaml).

There is no public door to the CRM and there never will be: the client base,
deal amounts and quote prices belong to the closed contour. Nothing of this goes
outward, topics included: a deal event carries the identifier, the pipeline and
the stage, but not the client's name and not the amount.

What appeared after 14 August and is not in the table above: a conversation with
a streamed answer and a "typing" indicator (`/api/assistant/v1/chat`, `/stream`,
`/typing`, `/rating`), a request raised from the chat that creates a lead with
a human-readable number `З-2026-0042`, staff presence and support hours, and
a visitor's rating of the answer. The answer is worded by **YandexGPT** on top
of search over published material: the engine is switched on by
`vedal.assistant.engine=yandexgpt`, and the links under the answer come from
the portal, not from the model. Since 7 September the gateway passes the
visitor's real address to the portal (`ClientAddress`) — before that the audit
log recorded the gateway's own address and the rate limit counted every visitor
as one client.

A note on duty of its own, because there are three facts now and they differ.
**Support hours** (`vedal.support.*`) are a promise that someone will answer,
and they do not name who. **Presence** is a fact about a set: is any desk open.
**Duty** (`duty_shift`, `/api/admin/v1/duty`) names a person for a day, and only
with it is there someone to hand a shift to and someone to ask about an
unanswered question. The day is counted in the support zone, not the server's;
there is one person on duty per day; past days are not edited. The portal
notices the disagreement between the schedule and presence on its own — someone
on duty who has not opened a desk during working hours — and shows it in the
admin UI. It sends no mail or messages about it: the recipient is the customer's
decision (issue #51).

A note on `assistant`: the limits live in `Guardrails` **before** the engine is
called, not in the prompt — a prompt is a request to the model, not a guarantee.
A refusal about price, delivery dates or availability, a clinical question, or
registration status answers within the current conversation and does **not**
hand it to a human. Intentional handoff happens only through the “Call a
specialist” button or an explicit request in the visitor's words. A technical
answer-generation failure remains a separate case and may queue the
conversation.

Being queued does not imply unconditional assistant silence. While a
conversation has no owner and no employee desk is open, Vedalina continues to
answer. Once an owner is assigned or an employee opens the admin UI, the human
takes over. Closed materials are physically unreachable: `LlmEngine` only goes
through `CatalogQuery`, `ContentQuery` and `DocumentQuery`, and public document
use is additionally blocked by the shared switch.

### 6.2 Frontend — the site and the admin UI

Next.js 16.3.0, React 19.2.8, App Router, TypeScript, CSS Modules, no external
dependencies besides Next and React.

Published site routes are `/`, `/products`, `/products/[slug]`, `/production`,
`/news`, `/news/[slug]`, `/service`, `/about`, `/contacts`, and
`/legal/privacy`. `/documents` temporarily returns `404`, while
`/production/archive` redirects to `/production`; both states follow the
customer's request pending material approval. Public product cards, five
categories, animations, a preloader, a map, and product-page tabs remain; the
documents tab is hidden by the shared switch.
On the contacts page the map is an embedded Yandex one, and its frame is
created only after consent in the cookie banner — by the same rule as the
Metrica counter. Without consent its place holds the CSS route scheme and the
“Построить маршрут” link: a fallback, not a stub, and it is the one that sits
in the static markup.
Since 7 September — a sitemap and `robots.txt` built from the portal, and the
site's own tab icon. Indexing is switched on by `NEXT_PUBLIC_SITE_URL`: while
it is empty, crawling is closed entirely, so the stand with draft copy does not
end up in search results.

Twenty-five admin routes. Site content: dashboard, products with a list and an
edit form, categories, news, documents, audit log, the Keycloak callback. CRM:
leads with conversion into a deal, clients, deals across three pipelines, quotes,
analytics in four dimensions, conversations and duty. The navigation is split
into those two sections:
a flat list of eleven items reads as a heap, while the sections match what the
person is doing — an editor edits the catalog, a manager runs deals.

The card version travels back in the edit form, and it is taken from the portal's
response rather than from what was read when the card was opened: without that,
a second save in a row gets a `409` out of nowhere. The version conflict itself
is handled apart from other refusals: the editor needs "re-read the card", not
"try again" — a retry would send the same stale version and get the same refusal
in a loop.

The site and the admin UI are separated by the `(site)` and `(admin)` route
groups, each with its own root layout. A nested layout cannot remove the parent's
chrome: the header, the footer, the preloader and the floating Vedalina would have
arrived in the admin UI too. The site's page addresses did not change: a group
name in parentheses never reaches the URL.

### 6.3 The gap is closed

The wire between the halves exists:

- the catalog, the news and the documents are read from the Public API at build
  time and become static — property №1 is intact, a backend outage does not take
  down an already built site;
- `LeadForm` submits to the Forms API with an `Idempotency-Key` and a consent
  version, `VedalinaChat` asks the assistant;
- the admin UI edits content through the Admin API under a Keycloak token.

The lead form stands in three places, and it is one form rather than three:
a short one in the hero of the home page, the full one with a topic selector
on `/contacts/`, and the full one with the product pinned on the product card
(`/products/<slug>/#quote`). On the card the product is filled in and not
asked for: the visitor chose it by reaching the page, and choosing the same
thing a second time is a choice that can be made wrong. The slug travels as
`productSlug`, so the manager sees in the admin UI and in the email which
product the lead is about.

The `Запросить КП` button on the card used to lead to `/contacts/`, where the
visitor looked for the form again, picked the topic again and had to recall
the product again.

The frontend has two API addresses, and that is not duplication: the catalog is
read at build time from inside the container (`VEDAL_API_INTERNAL_URL`), while
forms and the admin UI are called from the browser (`NEXT_PUBLIC_API_URL`). One
address for both cases is impossible: `localhost:8080` from inside a container
leads to the container itself, and `portal:8081` does not resolve in a browser.

**What is left of the old gap:** multilingual routing and SEO markup from
section 8 — those are about content, not about the wire. Yandex Metrica has
left that list: the code is written and waits for one counter id (issue #53).

### 6.4 Public state after the 16 September changes

Public pages no longer contain copy describing the tree as a marking sign, the
“Documentation confirmed” badge, or product-page document tabs. The documents
block is hidden on Home, and the category-filter row is hidden on News.
Organisation is required in the full `LeadForm` on product pages, Contacts, and
Service; the short `HomeLeadForm` on Home was not changed.

The site serves HTML with
`Cache-Control: public, max-age=0, must-revalidate`; hashed `/_next/` assets
retain immutable caching. This replaces the former almost year-long
`stale-while-revalidate` window that could show a stale page after a rollout.

The complete current route set and all 40 redirects are in the
[sitemap](frontend/sitemap.en.md). Reversible states are collected in the
[switch table](operations/public_feature_switches.en.md). The `site` startup
build and the first rollout incident are documented in the
[operations note](operations/frontend_startup_build.en.md).

---

## 7. What is needed on the backend side

| # | What | State and pitfalls |
| --- | --- | --- |
| 1 | Merge `dev` → `main`, push every layer branch | from outside the project still looks like documents without code |
| 2 | ~~Consumers read from topics~~ | ✅ Debezium reads the outbox from the write-ahead log, consumers take events from the topics, DLQ on `<topic>.dlq` after three attempts |
| 3 | ~~Keycloak instead of local accounts~~ | ✅ the portal verifies a realm token and parses `realm_access.roles`; local accounts remain as the `vedal.iam.mode=local` fallback. **Not closed: MFA** — enabled by realm policy in a deployed environment, which does not concern the portal |
| 4 | ~~Object storage and `FileStorage`~~ | ✅ S3 through the AWS SDK, one Yandex Object Storage — there is no second one anywhere. Two buckets, privacy anchored to the bucket. MinIO removed on 22 August: it was a second store that content leaked into unnoticed. Locally a directory on disk by default: it resembles nothing and is never confused with a bucket |
| 5 | ~~Grow `crm` to full: clients, deals, quotes, statuses, dealer and service funnels, correspondence history, attachments from approved documents, analytics by product/source/language/campaign~~ | ✅ the module is complete: `client`, `deal` with three pipelines, `quote` with lines, `interaction` (append-only), attachments from approved documents, four analytics dimensions. **Not closed:** CRM roles await the answer to question 12.3, the 1С exchange awaits 12.4 (the `inn`/`kpp`/`external_id` columns are in place already), the quote letter awaits Яндекс 360 SMTP |
| 6 | `MailSender` → Yandex 360 SMTP | letters still go to the log |
| 7 | `LlmEngine` → YandexGPT + pgvector, the pipeline text extraction → chunks with metadata → embeddings | **pitfall:** the EnterpriseDB PostgreSQL build for Windows does not ship pgvector. A `pgvector/pgvector:pg16` image is needed, and `compose.yaml` and `PostgresTestBase` must be switched **at the same time**, otherwise the tests diverge from development |
| 8 | Step 8 — the move: VM, Managed PostgreSQL, Managed Kafka, Object Storage, backups, monitoring | depends on Yandex Cloud, which does not exist yet. **Pitfall:** Managed PostgreSQL puts logical replication behind a separate flag, and without it the Debezium connector will not start |
| 9 | ~~Perimeter: `forward-headers-strategy`, body size limit~~ | ✅ on both the portal and the gateway. **Not closed:** the trusted proxy list is set at deployment |
| 10 | CI: a build with tests and an image, graceful shutdown, rollback by returning to the previous image | images are built by `compose --profile app`; there is still no deployment and no CI |
| 11 | ~~Remove the Thymeleaf admin pages~~ | ✅ gone, together with the login form and the cookie session. The portal has no browser-facing page left |
| 12 | Outbox cleanup | the table grows without bound. Deleting needs care around Debezium: `skipped.operations` already drops `d`, but the replication slot must read a row before it is removed |
| 13 | `ETag` on the public API | deliberately deferred: on twelve items the gain is zero and `Cache-Control` is already in place |
| 14 | ~~Dependency and image scanning in CI~~ | ✅ three checks that do not overlap: Dependabot on versions (PRs land in `infra`), CodeQL on the code, Trivy inside the images. Trivy's threshold is split in two: HIGH is visible in the report, CRITICAL with a released fix fails the build |
| 15 | ~~Restrict `/admin/**` at the proxy~~ | ✅ the `@admin` rule in the `Caddyfile` only lets private ranges through and is lifted by the `VEDAL_ADMIN_ALLOW` variable. **It only works where the proxy is ours:** there is no Caddy on the stand, where the door is held by the second factor — see question 12.3 |
| 16 | **Enable MFA in the realm** | a leaked editor password is the entire client base. Done and verified in the realm file (issue #42): the second factor is required by role through the `vedal-browser` flow. **Not switched on on the live Keycloak** — the commands are in [mfa_rollout.en.md](operations/mfa_rollout.en.md) |
| 17 | ~~Trim the application role's rights~~ | ✅ by migration `V15`, not by a runbook line: a `BEFORE TRUNCATE` trigger on the log plus revoking `UPDATE`/`DELETE`/`TRUNCATE`. The claim "a trigger does not protect against `TRUNCATE`" turned out to be wrong — see section 5.7. **Not closed:** the application connects as the schema owner, and in the stack as a superuser, for whom a revoke means nothing. A dedicated runtime role is the next step |
| 18 | **A rate limit at the proxy** | today it lives in process memory: with a second instance it becomes per-instance, and there is no global one |
| 19 | **Verify a backup restore** | the daily `pg_dump` exists and has been restored zero times. An unrestored backup is a hypothesis |
| 20 | **Secrets in Lockbox** | today they live as an `.env` file on a machine |

Items 14–20 come from reconciling the diagram with the code; the full breakdown
is in [architecture/target_architecture.en.md](architecture/target_architecture.en.md).

---

## 8. What is needed on the frontend side

| # | What | Details |
| --- | --- | --- |
| 1 | An API client plus `NEXT_PUBLIC_API_URL`, reading the catalog, news and documents through the Public API at build time | static stays static — property #1 must not break |
| 2 | `LeadForm` → `POST /api/forms/v1/leads` | `Idempotency-Key` (a uuid per form mount), the consent text version and time, a honeypot, success/error states, handling `202`. Plus attribution: the page language and `utm_campaign` are captured when the form mounts rather than when it is submitted — otherwise the tag is lost for everyone who did not fill the form on the very first page |
| 3 | `VedalinaChat` → `POST /api/assistant/v1/chat` | render the list of sources; when there are none, show the handoff to a human with contacts and forms rather than an invented answer. Buttons come from `GET /prompts`; «Позвать специалиста» is a separate door, `/chat/handoff` |
| 4 | Bring the routes in line with the sitemap | [sitemap](frontend/sitemap.en.md) requires `/press/` (Innoprom) and `/partners/` (Divisy, Morus MS, Smart Solution) — neither exists; `/news` was built instead of `/press`, and an unplanned `/about` was added. Either build them or update the map |
| 5 | ~~The product page from the API plus the list of documents per product~~ | ✅ done (issue #73): the product page reads both the product and the document listing from the Public API, and documents are selected by `product_slug`. One link rule for the whole site lives in `frontend/lib/documents.ts`: when a file exists the link points at the file in a new tab, when it does not the link points at the request form, and when the listing has no rows for the product there are no buttons at all. The badge shows the state of the file rather than the editor's intent: `pdf` without a file reads “По запросу”, not “PDF” — the listing does not return the file type, so the site is not entitled to promise a format. **Not closed:** there are no files in the `vedal-documents` bucket (#37) and no publication approvals (#35) — until then every row honestly leads to the request form |
| 6 | SEO: the metadata API, `sitemap.xml`, `robots.txt`, JSON-LD Product/Organization, canonical URLs | priority: `/products/`, `/products/<slug>/`, `/production/`, `/documents/` |
| 7 | ~~Yandex Metrica and the named events~~ | ✅ written (issue #53): the counter loads only when both conditions hold — `VEDAL_METRIKA_ID` is set and the visitor pressed “Accept” in the banner; if either fails, the `mc.yandex.ru` script is not loaded at all. The events from the [implementation checklist](frontend/implementation_checklist.en.md) are sent by a single handler reading `data-analytics` from the markup. **Not closed:** there is no counter id — we are waiting for the answer to question 12.11 and for the id itself from the customer |
| 8 | The multilingual skeleton `/en/`, `/zh/`, hreflang | content follows the approval of the Russian version; Hindi is a separate stage |
| 9 | Consent text before submitting any form, accessible forms and buttons | a Safety QA item, currently not covered |

---

## 9. Rules that must not be broken

They apply to the website, the admin panel and Vedalina alike:

- do not invent **prices**;
- do not invent **specifications**;
- do not invent **certificates or registration status**;
- do not invent **delivery times or availability**;
- do not publish **clinical claims** without approval;
- do not expose **internal or confidential documents**;
- Vedalina **does not diagnose and does not recommend treatment**, and does not
  promise a device's suitability for a clinical case without specialist review;
- missing data is **«ожидает уточнения»**, never a plausible invention;
- a questionable or sensitive question ends in a **handoff to a human**.

Visual rules: Vedalina is visible but secondary to the product and production
message; Smart Solution is secondary to VEDAL; restrained medical B2B with no
oversized marketing headlines and no decorative gradients.

---

## 10. Products and confirmed facts

Datasheets on company letterhead were received from the customer on **5 August
2026** — the first confirmed product data in the project. The analysis is in
[docs/products/README](products/README.en.md).

| Product | Category | Data status |
| --- | --- | --- |
| VEDAL R1, R2 — neonatal resuscitation system | resuscitation, neonatology | datasheet confirmed |
| VEDAL A-2000 — transformable incubator | neonatology, intensive care | datasheet confirmed |
| VEDAL Т-100 — thermoregulation system | neonatology, intensive care | datasheet confirmed |
| VV11, VP4, VN10, N6, N12, N15, N1, N2, N3 | ventilation, anesthesia, monitoring, neonatology | from the public website, no datasheets |

Catalog categories: neonatology, resuscitation, anesthesiology, monitoring,
intensive care.

Company details from the letterhead (confirmed, safe to use):

- ООО «ВЕДАЛ», 620135, Sverdlovsk region, Yekaterinburg, ul. Sovkhoznaya, bldg. 20В
- ИНН/КПП 5406826069/540601001
- 8-800-600-3449, +7 922 204 75 30
- sales@vedal-med.ru

**Still open:** registration and certification status for every item; product
photographs as separate files (the PDFs contain illustrations, but those are not
assets); the final list of items for the first release; whether the Т-100
indications for use may be published in the manufacturer's wording. There are no
prices in the datasheets, which matches the rule.

---

## 11. Contradictions between documents

They are recorded in the documents themselves as unresolved. They need a decision,
not an on-the-spot choice.

| What | Version A | Version B |
| --- | --- | --- |
| Employee count | 50 — [owner brief](architecture/vedal_portal_owner_brief.en.md) | 60 — [functional_requirements](strategy/functional_requirements.en.md), [infrastructure_architecture](architecture/infrastructure_architecture.en.md) |
| Message broker | Kafka — [spec](superpowers/specs/2026-08-06-vedal-portal-architecture-design.en.md) | Redis/RabbitMQ for indexing jobs — [infrastructure_architecture](architecture/infrastructure_architecture.en.md) |
| CRM | our own, inside the portal — owner brief | Bitrix24 / amoCRM / Yandex Tracker — an "open question" in infrastructure_architecture |
| Number of products | "about 10" — the brief | 13 names in functional_requirements, 12 cards in the code, 3 confirmed by datasheets |
| Corporate stack | Yandex 360 + Yandex Cloud + Managed PostgreSQL — decided in the brief | Yandex vs Google vs Kontur still being compared — infrastructure_architecture |

Until the broker discrepancy is resolved, `EventPublisher` stays a port: changing
the broker will not touch a single domain.

**The CRM discrepancy is resolved in favour of the brief: it is written inside
the portal.** The document hierarchy from section 2 places the owner brief above
infrastructure_architecture, and the "open question" there was written before the
brief answered it. The way back is still open: should the customer pick an
external CRM, deals travel there as a consumer of the `vedal.deals.v1` topic —
no separate port had to be introduced for that.

---

## 12. Open questions

**To the owner / Nikolay Nikolaevich:**

1. Do we build the CRM inside VEDAL Portal? — **we follow the brief: yes,
   inside.** The module is written. The answer is still needed: it closes the
   discrepancy from section 11.
2. Which data is forbidden to store in Yandex 360?
3. Who gets access to the CRM at the first stage? — **closed on 22 August:
   access is split by contour.** There are three roles:

   | Role | Open | Not visible |
   | --- | --- | --- |
   | `portal-sales` | leads, clients, deals, quotes, conversations, analytics | site content |
   | `portal-production` | products, categories, news, documents, images | the client base entirely |
   | `portal-admin` | everything, plus the audit log, the staff directory and erasure of personal data | — |

   Split by contour rather than by verb (reader/editor): a salesperson and
   the person who runs the site have different **subjects** of work, not
   different depth of access to one subject. The right to "read but not edit"
   a deal is a hierarchy nobody asked for; the right to "not see the deal at
   all" is what was asked for.

   The `portal-editor` role is removed: it duplicated `portal-admin`, and its
   own description said so.
4. Is an integration with 1C/Kontur needed right away? — **we assume "not right
   away":** a client carries `inn`, `kpp` and `external_id`, but there is no
   exchange. Adding the columns later means a migration plus a change to every
   form and every query; adding them now costs almost nothing.
5. Registration and certification status for each item.
6. The final product list for the first release and the priority order.
7. Which documents may be published and which stay in the internal contour only.
8. Photos and video of products, manufacturing and the exhibition stand — a link
   to the cloud folder.
9. The Innoprom materials.
10. The domain: `vedal-med.ru` or another option.
11. Whether Yandex Metrica may be installed on the public website — **and if
    so, the counter id.** Everything else is ready (issue #53): the counter,
    the consent banner with a decline option, the named events, the
    environment variables and the security policy. Without the id nothing
    is connected, and that is a working state rather than a failure: the
    site, the forms and Vedalina do not depend on analytics by a single
    line. Installing it before the answer is not allowed — a counter is a
    handover of visitor data to a third party.
12. Whether the assistant may be publicly called Vedalina and shown in the hero.
13. Duty: whether a schedule is needed at all, who fills it in, and where to
    send word about a disagreement with presence. The mechanics are done in
    full (issue #51): the "Duty" section shows two weeks ahead, the name of
    whoever is on duty stands above the conversation list, a shift is handed
    over with one button, and the portal itself works out the disagreement —
    someone on duty who has not opened a desk during working hours. Exactly
    one thing is deliberately not done: mail about that disagreement. We do
    not invent the recipient, and a wrong recipient for a night-time email
    costs more than no email at all.

**Technical, awaiting confirmation:**

1. Acceptable data loss and time to recovery — 5 minutes and 1 hour proposed.
2. Retention period for leads, conversations and mail — 3 years proposed.
   **The auto-cleanup mechanism is written and off by default** (issue #47):
   leads — `RetentionSweep` in `crm`, conversations —
   `ConversationRetentionSweep` in `chat`, mail — `MailRetentionSweep` in
   `notifications`. Until the period is confirmed it must not be switched on —
   erasure is irreversible, and there is nowhere to restore from either, since
   there are no backups. Each kind of data turns on with its own property
   (`vedal.privacy.retention`, `.chat`, `.mail`), a period value such as `P3Y`;
   without the property — and with an empty value for it — the bean is not
   created at all. The three variables reach the portal through compose and
   are listed, empty, in `backend/.env.example`. The order of switching on,
   what exactly is erased from each carrier and what happens on the first pass
   — [data_retention.en.md](operations/data_retention.en.md).
3. ~~Whether `/admin` is closed at the network level or left behind a password
   and MFA.~~ **Closed on 8 September (issue #42): the main barrier is a
   password and a second factor; the network is a second, independent layer
   where the proxy is ours.** Not "or": first the thing that works everywhere.
   The network only works where the proxy is ours — in the target environment
   that is `@admin` in the `Caddyfile`; on the stand there is no Caddy at all,
   the admin panel is served by a shared nginx next to somebody else's
   production sites, and a restriction there would have to be made by somebody
   else's hands. A barrier that depends on somebody else's schedule is not a
   barrier. The second factor is the same everywhere and lives in the realm
   file. The `private_ranges` default is not being removed: it costs nothing
   and cuts off what never reaches the login form. Lifting it
   (`VEDAL_ADMIN_ALLOW="0.0.0.0/0 ::/0"`) is only allowed once the second
   factor is confirmed working for every holder of `portal-admin` and
   `portal-sales`. The reasoning, the order and the rollback —
   [mfa_rollout.en.md](operations/mfa_rollout.en.md).
4. Which cloud. The storage runs on S3, and moving between S3-compatible
   stores changes the address and the keys, not the code.
5. When MFA is switched on in the realm. This does not concern the portal: it
   verifies an issued token and does not know how many factors were presented.
   The realm file and the rollout order are ready and verified (issue #42,
   [mfa_rollout.en.md](operations/mfa_rollout.en.md)): the second factor
   (TOTP) is mandatory for `portal-admin` and `portal-sales`, not required for
   `portal-production`. It is tied to the role by the `vedal-browser` sign-in
   flow rather than assigned to a person by hand, so existing employees need
   nothing assigned and nobody can be forgotten. Switching it on on the live
   Keycloak is the owner's decision and action, not an automatic consequence of
   the git change: `--import-realm` does not overwrite a running realm, and the
   commands for the live stand are written out step by step in the document.
6. Who runs Keycloak in a deployed environment and how employees are created in it.

---

## 13. Order of work

1. **Merge `dev` → `main`, push the layer branches.** Cheap, and it removes the
   impression of a dead repository with no code.
2. **Exercise the admin UI on live data.** It is up, covered by tests and checked
   by hand, but has not yet met a real editor.
3. **Deploy it somewhere.** The stack comes up with one command
   (`./scripts/up.sh`), there is a production profile with TLS and backups, and
   CI runs the tests and brings the stack up — but there is still no deployed
   environment. That is the next bottleneck, and it is not in the code.
4. ~~**The full CRM**: clients, deals, quotes, funnels, analytics.~~ Done. What
   remains is exercising it on live data together with the admin UI and getting
   answers to questions 12.3 and 12.4 — roles and the 1С exchange depend on them.
5. Yandex 360 SMTP, YandexGPT + pgvector, multilingual routing, the move to
   Yandex Cloud.

The full roadmap is [operations/roadmap.en.md](operations/roadmap.en.md): stage 0
(collecting materials) → 1 (public MVP) → 2 (corporate contour) → 3 (AI search) →
4 (multilingual) → 5 (VLM for manufacturing and service).

---

## 14. Where to find what

| You need | File |
| --- | --- |
| Target architecture, contours, budget | [architecture/vedal_portal_owner_brief.en.md](architecture/vedal_portal_owner_brief.en.md) |
| The target architecture diagram with a state per block | [architecture/target_architecture.en.md](architecture/target_architecture.en.md) |
| Technical decisions, accepted and rejected | [superpowers/specs/2026-08-06-vedal-portal-architecture-design.en.md](superpowers/specs/2026-08-06-vedal-portal-architecture-design.en.md) |
| What is built on the backend, how to run it, the ports | [../backend/README.en.md](../backend/README.en.md) |
| The public API contract: entry points, forms, entities, errors | [api/README.en.md](api/README.en.md), [api/vedal-openapi.yaml](api/vedal-openapi.yaml) |
| The admin API contract | [api/vedal-admin-openapi.yaml](api/vedal-admin-openapi.yaml) |
| Single entry point, routes, body limit | [../backend/api-gateway/README.en.md](../backend/api-gateway/README.en.md) |
| Sign-in, roles, the local realm, Keycloak's two addresses | [../backend/keycloak/README.en.md](../backend/keycloak/README.en.md) |
| The outbox → Kafka connector, its settings, what to check on failure | [../backend/debezium/README.en.md](../backend/debezium/README.en.md) |
| Bring everything up on a clean machine | [../scripts/up.sh](../scripts/up.sh), [../scripts/up.ps1](../scripts/up.ps1) |
| Stack settings and where they live | [../backend/.env.example](../backend/.env.example) |
| Deployed environment: secrets, TLS, backups | [../backend/compose.prod.yaml](../backend/compose.prod.yaml), [../backend/proxy/Caddyfile](../backend/proxy/Caddyfile) |
| What CI checks | [../.github/workflows/ci.yml](../.github/workflows/ci.yml) |
| The boundaries of a specific module | `backend/<module>/README.en.md` |
| How to run the frontend, content rules | [../frontend/README.en.md](../frontend/README.en.md) |
| Site structure and routes | [frontend/sitemap.en.md](frontend/sitemap.en.md) |
| Reversible public feature switches | [operations/public_feature_switches.en.md](operations/public_feature_switches.en.md) |
| Site startup build and the 16 September incident | [operations/frontend_startup_build.en.md](operations/frontend_startup_build.en.md) |
| Data models for page, product, document, form, assistant | [frontend/content_model.en.md](frontend/content_model.en.md) |
| What each page must contain | [frontend/page_briefs.en.md](frontend/page_briefs.en.md) |
| Acceptance checklist and analytics events | [frontend/implementation_checklist.en.md](frontend/implementation_checklist.en.md) |
| Vedalina's rules and limits | [strategy/vedalina_assistant_spec.en.md](strategy/vedalina_assistant_spec.en.md) |
| The full business requirements | [strategy/functional_requirements.en.md](strategy/functional_requirements.en.md) |
| Stages and timelines | [operations/roadmap.en.md](operations/roadmap.en.md) |
| Infrastructure phases and open questions | [architecture/infrastructure_architecture.en.md](architecture/infrastructure_architecture.en.md) |
| Confirmed product data and company details | [products/README.en.md](products/README.en.md) |
| What was requested from the customer | [requests/nikolay_materials_request.en.md](requests/nikolay_materials_request.en.md) |
| Design direction and the three variants | [strategy/frontend_design_handoff.en.md](strategy/frontend_design_handoff.en.md), [strategy/frontend_variants.en.md](strategy/frontend_variants.en.md) |
| SEO and multilingual planning | [strategy/content_and_seo_plan.en.md](strategy/content_and_seo_plan.en.md) |
| Competitors and partners | [strategy/competitor_notes.en.md](strategy/competitor_notes.en.md) |
| The customer presentation | [strategy/nn_presentation_outline.en.md](strategy/nn_presentation_outline.en.md) |
| The detailed catalog module plan (a template for the next ones) | [superpowers/plans/2026-08-06-catalog-module.en.md](superpowers/plans/2026-08-06-catalog-module.en.md) |
| Documentation rules, the glossary | [documentation_rules.en.md](documentation_rules.en.md) |

---

## How to maintain this document

It is an overview and it goes stale first. The rule: if a **decision** changes
(architecture, stack, module boundaries, release scope) — this file is edited. If
an **implementation** changes inside an already-described decision — the README of
the corresponding module is edited, and only a line in "Current state" comes here.

The numbers in section 6 were obtained by counting across the repository — when
updating, recount them rather than rewriting them from memory.
