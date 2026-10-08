# MuseonUrania / VEDAL Portal

[Русский](README.md) · **English**

Public portfolio version of the repository: website, admin workspace, API and
AI assistant for a medical equipment manufacturer. Real credentials, contracts,
acts, invoices, commercial terms, cloud keys, SMTP, S3 and YandexGPT secrets are
not stored in git and are handed over to the customer in a separate official
package.

## What Is Inside

VEDAL Portal combines the public website with a protected staff workspace:

- public pages: home, about, products, service, production, documents, news,
  contacts and personal data policy;
- product catalog with cards, SEO metadata and media from object storage;
- lead and service request forms with database persistence and notifications;
- admin workspace for pages, products, documents, news, leads, clients, deals,
  quotes, staff and audit log;
- Vedalina, the AI assistant: public answers use approved materials only, while
  the closed contour can work with an extended knowledge base;
- preparation for CRM handoff and integration with the customer's corporate
  contour.

## Stack

| Layer | Technologies |
| --- | --- |
| Frontend | Next.js 16, React 19, TypeScript, CSS Modules, Vitest, Testing Library |
| Backend | Java 25, Spring Boot 4.1, Maven multi-module, Spring Data JPA, Flyway |
| API | REST, OpenAPI/Swagger, separate gateway |
| Data | PostgreSQL 16, transactional outbox, Kafka 3.9 in KRaft mode |
| Files | S3-compatible object storage through AWS SDK |
| Auth | Keycloak, OAuth2/OIDC, PKCE, protected-contour roles |
| AI | YandexGPT, GigaChat or Cloud.ru Foundation Models (switched by configuration), RAG pipeline, pgvector in the target architecture |
| Infrastructure | Docker Compose, Caddy/reverse proxy, systemd autodeploy, Yandex Cloud VM |

## Quick Start

Docker with Compose v2 is the only prerequisite. Java, Node and Maven do not
need to be installed on the host: the local stack is built inside containers.

```bash
./scripts/up.sh
```

On Windows:

```powershell
.\scripts\up.ps1
```

The script checks Docker, creates a local `backend/.env`, builds the images and
waits for the site to become healthy.

| Address | Purpose |
| --- | --- |
| `http://localhost:8080` | site and gateway |
| `http://localhost:8080/admin/` | admin workspace |
| `http://localhost:8080/swagger-ui.html` | Swagger UI |
| `http://localhost:8180` | local-stack Keycloak |

Stop the stack without deleting data:

```bash
docker compose -f backend/compose.yaml --profile app down
```

Recreate local data from scratch:

```bash
docker compose -f backend/compose.yaml --profile app down -v
```

## Environment Variables

Only templates are stored in the repository:

- `backend/.env.example`;
- `backend/.env.host.example`;
- `frontend/.env.example`.

Production and stand `.env` files are never committed. They include PostgreSQL,
Keycloak, SMTP, S3/Object Storage, YandexGPT and domain settings. Customer
handover files are prepared as a separate official package outside the
repository.

## Development

Start infrastructure without the backend application and the site:

```bash
docker compose -f backend/compose.yaml up -d
```

Then run backend and frontend from the developer machine:

```bash
cd backend && ./mvnw spring-boot:run -pl app
cd frontend && npm install && npm run dev
```

Checks:

```bash
cd backend && ./mvnw test
cd frontend && npm test
cd frontend && npm run lint
```

## Public Repository Rules

The following must not be committed:

- `.env`, keys, tokens, passwords, database dumps and private certificates;
- contracts, acts, invoices, requisites and commercial calculations;
- email drafts, signatures and personal contractor contacts;
- customer PDFs, presentations, source media and temporary `outputs/`;
- credential handover documents and closed-service operational instructions.

If such files are needed for delivery, they live in a separate official package
outside the repository.

## GitHub Releases

A GitHub Release is a named project version based on a git tag, for example
`v1.0.0`. It can contain release notes, attached files and a link to the exact
state of the code. It is not the deployment itself, but it works well as a
checkpoint: “the version shown to the customer” or “the public portfolio-safe
version”.
