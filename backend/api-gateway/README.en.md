# API Gateway

[Русский](README.md) · **English**

The single entry point of the contour. In the
[owner brief](../../docs/architecture/vedal_portal_owner_brief.en.md) this is the
DMZ / Integration Gateway: one address is visible from outside, the portal and
the site sit behind it.

A separate application, not a portal module. `backend/gateway/` is something
else: that folder documents the lead-intake module and contains no code.

## Why bother, when the portal already answers

Routing is the least interesting thing it provides.

**The site and the API end up on one origin.** CORS drops out of the perimeter
entirely: a browser does not make a cross-origin request when the domain is the
same. Before the gateway, the allowed-origins list was part of the portal's
configuration, and a mistake in it meant either broken forms or an open door.

**The body limit and token verification sit in one place** instead of in every
application behind the gateway.

**The client's real address reaches the portal** the same way regardless of what
stands in front of the gateway. Without this the rate limit counts every visitor
as one client and the audit log records the proxy's address instead of the user's.

## What it does not provide

**Trust.** The portal verifies the token itself and refuses a request that
arrived bypassing the gateway. Roles, permissions and audit entries remain its
work. Splitting that decision in two means one day catching a divergence where
the gateway lets a request through and the portal refuses it — and looking for
the cause in two configurations at once.

Token verification at the gateway is switched on with
`VEDAL_GATEWAY_VERIFY_TOKENS=true` and does exactly one thing: the refusal
arrives at the boundary, and a request without a token never reaches the
application. Even then the gateway checks only the signature and expiry — not
roles.

## Routes

Order matters: the list is read top to bottom, so the specific ones come first.

| Route | Where to | Note |
| --- | --- | --- |
| `/api/admin/**` | portal | body limit 21 MB — 20 for the file plus headroom for form fields |
| `/api/**` | portal | the public doors |
| `/v3/api-docs/**`, `/swagger-ui/**` | portal | springdoc serves them outside the `/api` prefix |
| `/**` | site | including `/admin/**` — the Next.js admin UI |

There is no route to server-rendered admin pages because there are none left:
the portal has no browser-facing page at all, and with them went the login form,
the cookie session and the CSRF token. Sign-in goes to Keycloak directly, and
`/admin/**` is served by the site.

## Versions

Spring Boot 4.0.8, not 4.1.0 as in the portal. Spring Cloud Gateway 5.0.x is
built against 4.0.x, and that is the only supported pair.

The version skew is harmless precisely because this is a separate process: the
gateway talks to the portal over HTTP, not over the classpath. For the same
reason there is no shared Maven reactor — it would force one Boot version on
both.

## Settings

| Variable | Default | What it sets |
| --- | --- | --- |
| `VEDAL_GATEWAY_PORT` | `8080` | the gateway's port |
| `VEDAL_PORTAL_URL` | `http://localhost:8081` | the portal's address |
| `VEDAL_SITE_URL` | `http://localhost:3000` | the site's address |
| `VEDAL_GATEWAY_VERIFY_TOKENS` | `false` | whether to verify the token at the perimeter |
| `VEDAL_OIDC_ISSUER` | empty | realm address; required when `verify-tokens=true` |

The `21MB` body limit in `application.yaml` is aligned with the portal's
`vedal.storage.max-file-size`. A smaller limit here would drop the upload with a
413 before the application, and the editor would see the gateway's page instead
of an explanation.

## Running it

As part of the stack:

```bash
docker compose -f backend/compose.yaml --profile app up -d --build
```

On its own:

```bash
cd backend/api-gateway && ./mvnw spring-boot:run
```

Liveness — `GET /actuator/health`. The other endpoints are closed: the gateway's
route list is a map of the system, for the same reason the portal exposes only
health.
