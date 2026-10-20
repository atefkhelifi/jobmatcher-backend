# Installation and deployment

## What you need

| | Minimum | Notes |
|---|---|---|
| Docker with Compose v2 | yes | runs the database, API and web app |
| RAM | 2 GB free without AI, 8 GB with `qwen3:8b` | building the images needs about 2 GB more |
| Disk | 3 GB, plus about 6 GB for the default model | |
| Java 21, Node 22.22+ | only for development | Angular 22 needs a recent Node |

## 1. Run it with Docker

```bash
git clone <your fork or this repository> jobmatcher && cd jobmatcher/infra
cp .env.example .env
```

Edit `.env`:

```bash
DB_PASSWORD=<any long random string>
APP_ENCRYPTION_KEY=<output of: openssl rand -base64 32>
AI_PROVIDER=mock          # no model needed; use ollama for the grammar review and letter rewriting
```

```bash
docker compose --env-file .env up --build        # without a model
docker compose --env-file .env --profile ai up --build   # with Ollama (pulls qwen3:8b, about 5 GB)
```

Open http://localhost:8088, register, and follow the walkthrough in the [README](../README.md#walkthrough).

> **Back up `APP_ENCRYPTION_KEY`.** CV text, parsed profiles, letters, emails, notes and contact details are encrypted with it.
> Without the key they cannot be read again. Back up the `dbdata` volume as well.

### Choosing the AI provider

| `AI_PROVIDER` | What you get |
|---|---|
| `mock` | Deterministic stand-in, no model. CV extraction, analysis, matching, letters from templates, emails, tracker all work. The grammar review and AI letter rewriting report "not available". |
| `ollama` | Local model through Ollama. Set `OLLAMA_MODEL` (default `qwen3:8b`; use `qwen3:4b` on CPU-only machines). Without a running Ollama the app says so and falls back to the deterministic paths. |

To use another model server, implement `ai.jobmatcher.ai.TextGenerator` and register it in `AiConfig` (see the README).

### Enabling more job sources

| Source | How |
|---|---|
| Arbeitnow | works without setup |
| Company boards (Greenhouse) | `APP_JOBS_GREENHOUSE_BOARDS=gitlab,stripe` |
| France Travail | free credentials from francetravail.io: `APP_JOBS_FRANCETRAVAIL_CLIENT_ID`, `APP_JOBS_FRANCETRAVAIL_CLIENT_SECRET` |
| A single page | paste its address on the Jobs page (needs `schema.org/JobPosting` data and robots.txt permission) |

Read each source's terms (linked from the Jobs page) before enabling it.

## 2. Put it on the internet safely

The bundled nginx speaks plain HTTP on one port. Do **not** expose the API port; only the web container is published. Terminate
TLS in front of it and set `COOKIE_SECURE=true`. Example with Caddy:

```
jobs.example.org {
    reverse_proxy localhost:8088
    header Strict-Transport-Security "max-age=31536000; includeSubDomains"
}
```

Checklist for a public deployment:

- [ ] HTTPS in front, `COOKIE_SECURE=true`.
- [ ] A strong `DB_PASSWORD`; the database port is not published by the Compose file, keep it that way.
- [ ] `APP_ENCRYPTION_KEY` stored outside the repository and backed up.
- [ ] Only one API replica (rate limits and the scheduler are in memory; see Known limits).
- [ ] Registration: anyone who can reach the site can create an account. Restrict access at the proxy if it is for you alone.
- [ ] Logs: the `audit` logger records sign-ins and deletions with a user id and a truncated address, never an email. Route it where you need it.
- [ ] Updates: run `python scripts/audit-deps.py` and rebuild regularly.

## 3. Development setup

```bash
# database
docker run --rm -d --name jm-db -p 5432:5432 -e POSTGRES_DB=jobmatcher -e POSTGRES_USER=jobmatcher \
  -e POSTGRES_PASSWORD=jobmatcher pgvector/pgvector:pg17

# API, http://localhost:8080 (OpenAPI UI at /api/docs)
cd backend
APP_ENCRYPTION_KEY=$(openssl rand -base64 32) AI_PROVIDER=mock ./mvnw spring-boot:run

# web, http://localhost:4200, proxies /api to :8080
cd frontend && npm ci && npx ng serve
```

## 4. Tests

| Command | What it covers |
|---|---|
| `cd backend && ./mvnw verify` | about 240 unit and integration tests; Testcontainers starts PostgreSQL |
| `cd frontend && npx ng test --watch=false` | about 40 component and unit tests |
| `cd e2e && npm ci && npx playwright install chromium && npx playwright test` | the ten Definition-of-Done steps in a real browser, after building the jar and the web app |
| `python scripts/audit-deps.py` | every Maven and npm dependency against the OSV vulnerability database |
| `RUN_LIVE_TESTS=true ./mvnw test -Dtest=LiveSourcesTest` | optional: calls the real Arbeitnow API |

## 5. Upgrading

Database migrations run automatically (Flyway) and are forward-only. Back up the database before upgrading. Never edit a
migration that has been released; add a new one.

## 6. Troubleshooting

| Symptom | Likely cause |
|---|---|
| `APP_ENCRYPTION_KEY` error at start | the key is missing or not 32 bytes in Base64 |
| The Jobs page shows a source as failing | the source answered with an error or changed its format; the message is shown on the page |
| "The language model is not available" | `AI_PROVIDER=ollama` but Ollama is not running or the model is not pulled (`ollama pull qwen3:8b`) |
| 429 answers | per-user rate limits (see `app.rate-limit.*` in `application.yml`) |
| Scanned PDF is refused | there is no OCR yet; paste the CV text instead |
| Docker build killed or exits with `0xc0000142` (Windows) | the machine ran out of memory; close other programs and retry |
