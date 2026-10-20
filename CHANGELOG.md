# Changelog

## 0.2.0

The extras from the project brief. Details in `docs/extras.md`.

### Added

- **Job alerts and notifications**: in-app notifications for new matching offers (per saved search, with a minimum score),
  follow-ups that became due and interviews within 24 hours; bell with unread count; both kinds can be switched off.
- **Statistics** over the user's own applications: funnel, stages, countries, roles, requested skills, time per stage, results per
  CV, with a small-sample caution and no benchmarks.
- **Scam and quality warnings** on every job page, each with the quoted words, in English and French.
- **CV version comparison**: readability, keyword coverage and match score for a chosen job, and factual differences between two CVs.
- **Learning plan** from the gaps in open applications, with a curated list of free resources and `scripts/check-links.py`.
- **Interview preparation**: questions from the ad with evidence quoted from the CV, STAR frames, honest gap answers, revision
  topics and a mock mode that keeps nothing.
- **Employer overview**: the offers the platform has seen for a company, grouped across spellings, and the user's own history.

### Known limits

- Alerts are as fresh as the automatic search interval and are in-app only (no email, push or SMS).
- The warning rules are heuristics in English and French and miss scams that avoid the listed wording.
- The learning list covers skills in the dictionary that have a well-known free resource, not all of them.
- Employer overview shows only what the platform has seen; there are no ratings, reviews or financial data.

### Changed

- Saving an offer from the jobs page now records the CV that was selected, so results can be compared per CV; a CV deleted since is
  ignored instead of blocking the save.
- Applications keep the offer's country.

## 0.1.0

First release: the minimum viable product from the project brief.

### Added

- **Accounts and privacy**: registration, sign-in, cookie sessions with CSRF protection, Argon2id passwords, rate limits, data
  export (CVs, preferences, saved searches, applications, documents) and full account deletion, an audit log without personal
  data, encryption of CV text and personal notes at rest, retention of expired offers.
- **CV intake and analysis**: PDF, DOCX and pasted text, extraction to an editable profile in French and English, ranked
  improvement recommendations, optional language-model grammar review whose findings must quote the CV, export as Markdown.
- **Job discovery**: pluggable sources (Arbeitnow, Greenhouse boards, France Travail, single pages with structured data), safe
  fetching, cross-source de-duplication, expiry detection, source health, saved and automatic searches.
- **Matching**: explainable 0 to 100 compatibility score from eight weighted criteria, required versus preferred skills, evidence
  from the CV, gaps, blockers, CV suggestions, learning priorities, sorting and filtering by score. Method in `docs/scoring.md`.
- **Documents**: grounded cover letters and application emails in French and English, version history, edit warnings, PDF, DOCX,
  TXT and `.eml` export. Nothing is ever sent.
- **HR contacts**: published addresses, named contacts and application links with their sources; addresses are never guessed.
- **Application tracker**: ten stages with legal transitions, timeline, notes, contacts, interviews, reminders, attachments,
  duplicate warning, calendar export.
- **Quality**: about 240 backend tests, about 40 frontend tests, a Playwright journey through the ten Definition-of-Done steps,
  a prompt-injection test set, and a dependency audit against the OSV database.

### Known limits

- No OCR for scanned documents. Letters exist in French and English only. Text similarity is lexical, not semantic.
- Single API instance. DNS rebinding is a documented residual risk of the URL fetcher.
- Interview preparation, job alerts, analytics, learning plans, company intelligence, scam warnings and CV comparison are planned,
  not built.
