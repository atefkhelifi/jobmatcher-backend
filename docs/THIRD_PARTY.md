# Third-party components

This list covers what ships or runs in the current milestone. Versions are pinned in `backend/pom.xml` and `frontend/package.json`. Verify licenses again when upgrading; the CI plan includes an automated license gate (not yet implemented).

| Component | Use | License |
|---|---|---|
| Spring Boot, Spring Security, Spring Data JPA | Backend framework | Apache-2.0 |
| Hibernate ORM | Persistence | Apache-2.0 (also offered under LGPL-2.1; confirm for the pinned version) |
| Flyway (community) | Migrations | Apache-2.0 |
| PostgreSQL JDBC driver | Database access | BSD-2-Clause |
| PostgreSQL, pgvector | Database | PostgreSQL License |
| Apache Tika | Text extraction | Apache-2.0 |
| jsoup | HTML to text, JSON-LD extraction | MIT |
| Bouncy Castle (bcprov) | Argon2 implementation | MIT |
| springdoc-openapi | API documentation UI | Apache-2.0 |
| Angular, RxJS | Frontend | MIT |
| Vitest, jsdom | Frontend tests | MIT |
| Playwright (with Chromium) | End-to-end tests and README screenshots | Apache-2.0 (Chromium: BSD-3-Clause and others) |
| Testcontainers, JUnit 5, AssertJ | Backend tests | MIT / EPL-2.0 / Apache-2.0 |
| Apache POI, PDFBox | Test fixtures; also used by Tika | Apache-2.0 |
| nginx | Static hosting and proxy (container) | BSD-2-Clause |
| Ollama | Optional local model runtime | MIT |
| Qwen3 (default model, `qwen3:8b`) | Optional text generation | Apache-2.0 |
| Bricolage Grotesque, IBM Plex | Web fonts loaded from Google Fonts | SIL OFL 1.1 |

Deliberately avoided: PyMuPDF (AGPL-3.0) and models under restrictive or custom licenses, because they would constrain how this project can be licensed and reused.

## Data sources

| Source | Access | Terms |
|---|---|---|
| Arbeitnow job board API | public, no key | https://www.arbeitnow.com/blog/job-board-api (free use, please link back to the offer, which every card does) |
| Greenhouse Job Board API | public per company board | https://developers.greenhouse.io/job-board.html |
| France Travail Offres d'emploi v2 | free OAuth client | https://francetravail.io/data/api/offres-emploi |
| Pages submitted by a user | only if robots.txt allows and the page publishes schema.org JobPosting data | the site's own terms |

Not used on purpose: LinkedIn, Indeed and Welcome to the Jungle, whose terms do not permit automated collection.

## Learning resources

The learning plan links to free official documentation and courses (Oracle, Spring, MDN, Docker, Kubernetes, HashiCorp, Microsoft
Learn, Google, OWASP and others), listed in `backend/src/main/resources/skills/learning-resources.csv`. The application only links to
them; it does not copy or redistribute their content. Each belongs to its publisher and is subject to the publisher's terms.
`scripts/check-links.py` verifies that the links still answer.
