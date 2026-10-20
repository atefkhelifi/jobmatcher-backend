# Contributing

Thanks for helping. A few rules keep the project trustworthy.

## Ground rules

1. **Never invent.** Unknown job data stays `null` and is shown as "not stated". Generated documents may only use facts from the
   user's reviewed CV. A change that adds a claim, a skill, a contact or a figure that the sources do not state will not be merged.
2. **Respect sources.** New job sources must come from an official API, a feed or a page that permits automated access. Do not add
   scraping that needs a login, a CAPTCHA or evasion of rate limits. See [docs/adding-a-job-source.md](docs/adding-a-job-source.md).
3. **No protected characteristics** in matching or ranking. Only job-relevant evidence and preferences the user set.
4. **Privacy by default.** Free text a user writes and third-party contact details are encrypted; logs carry no personal data.
5. **Tests with every change.** Use synthetic fixtures; never commit real CVs, names or addresses.

## Workflow

```bash
cd backend  && ./mvnw verify
cd frontend && npx ng test --watch=false && npx ng build
cd e2e      && npx playwright test          # after building the jar and the web app
python scripts/audit-deps.py                # after changing dependencies
```

- Backend code: package per feature (`web`, `service`, persistence together), constructor injection, records for DTOs, owner-scoped
  repository methods (`findByIdAndUserId`).
- Frontend code: standalone components, signals, reactive forms, every user-facing string in both languages in
  `core/i18n.ts` (the type system checks the French dictionary is complete).
- Database changes: add a Flyway migration; never edit a released one.
- Comments explain why, not what. Keep functions small and name things after the problem they solve.

## Adding things

- A job source: [docs/adding-a-job-source.md](docs/adding-a-job-source.md).
- A letter language: implement `LetterLocale` (see `documents/FrenchLocale.java`) and register it as a bean.
- An AI provider: implement `TextGenerator` and register it in `AiConfig`.
- A skill: add a line to `backend/src/main/resources/skills/skills.csv` (canonical;category;aliases).

## Commit messages

Imperative mood, a short summary line, then a paragraph explaining the reason when it is not obvious.

## Two repositories

The backend repository (this one) holds `backend/`, `infra/`, `docs/`, `e2e/` and the CI. The Angular app is in
`atefkhelifi/jobmatcher-frontend` (public). CI checks it out into `frontend/`; no secret is needed.
