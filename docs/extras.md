# Alerts, statistics and preparation tools

Milestone 7 adds seven helpers around the core flow. Each follows the same rules as the rest of the project: nothing is invented,
unknown stays unknown, and every claim can be traced to something the user can open.

| Helper | Where | Backend |
|---|---|---|
| Job alerts and notifications | bell in the header, `/app/notifications`, minimum score on saved searches | `notifications` |
| Application statistics | `/app/analytics` | `analytics` |
| Scam and quality warnings | top of every job page | `jobs.JobQualityAnalyzer` |
| CV version comparison | `/app/cvs/compare` | `comparison` |
| Learning plan | `/app/learning` | `learning` |
| Interview preparation | "Interview" tab on a job | `interview` |
| Employer overview | bottom of the job description tab | `company` |

## Job alerts and notifications

An automatic saved search runs every few hours. When a run finds offers that were not in the catalogue before, `AlertService`:

1. keeps the ones that still fit the search's own filters (keywords, location, remote only);
2. scores each against the user's newest CV with the normal matching engine (a user without a CV is told about new offers that fit
   the search, without scores);
3. keeps those at or above the search's **minimum score** (default 60);
4. creates one notification per run listing the count and the best three.

Two more notification types come from `NotificationDispatcher`, polled every ten minutes: a follow-up reminder that has become due,
and an interview starting within 24 hours. Each is sent once (`notified_at`). A reminder's private text is never copied into a
notification; it names the role only.

Both switches in **Preferences** (new matches, reminders) apply. Notifications are stored as a code plus parameters, so they are
shown in the language the user has chosen when reading them, and they are deleted after 90 days. Nothing leaves the application:
there is no email, push or SMS channel.

## Application statistics

Computed from the user's own applications and their timeline only:

- funnel: tracked, applied, reached an interview or assessment, offers, rejected after applying, still waiting;
- counts by stage, country and role (a role ignores gender markers such as "(m/f/d)" and case);
- the skills most often named in the tracked offers;
- average days spent in each stage (an open stage counts until now);
- results by CV version, when the CV was chosen while saving the offer.

Cautions built into the output: fewer than 10 applications is flagged as a small sample; a "best CV" is named only when at least two
CVs have 5 or more applications each and one has a strictly higher interview rate, and even then the text says it may be chance.
There are no benchmarks against other users and no predictions.

## Scam and quality warnings

`JobQualityAnalyzer` applies plain patterns in English and French to the ad and reports each hit with the quoted words:

| Code | Severity | Looks for |
|---|---|---|
| `FEE_REQUESTED` | caution | registration, training or deposit fees, "buy your own equipment", "frais d'inscription" |
| `SENSITIVE_DOCUMENTS` | caution | a request to send a passport, ID card, bank details, social security number |
| `MESSAGING_APP_CONTACT` | caution | WhatsApp, Telegram, WeChat, Signal as the contact route |
| `UNREALISTIC_EARNINGS` | caution | "easy money", guaranteed income, large pay per day for no experience |
| `URGENCY_PRESSURE` | note | "urgent hiring", "limited spots" |
| `PERSONAL_EMAIL_CONTACT` | note | a webmail address as the contact |
| `NO_COMPANY`, `VERY_SHORT` | note | missing employer name, a description under 250 characters |

These are **warnings, not accusations**. The page says so, and a legitimate ad can trigger one (a bank that really needs your
statements, say). Rules are in one file, are unit tested in both languages, and a mention that is not a request (the word "bank
statements" in an ad about banking software) does not fire.

## CV version comparison

Pick two CVs and, optionally, a job. The comparison shows:

- **Readability**: words, bullet count, average bullet length, share of bullets with a number, bullets over 30 words, sections present.
- **Keyword coverage** for the job: required and preferred skills shown or not shown, and the match score of each version.
- **Consistency of facts**: employers, dates, titles, degrees, contact details and years of experience that differ between the two.
  Jobs are paired by employer, so a changed end date is one difference, not a job removed and another added. A value missing from one
  version is not a conflict.

The tool lists differences and never says which version is correct.

## Learning plan

For the user's open applications, the gaps the matching engine found (skills the ad requires or prefers and the CV does not show)
are added up. Skills required by the most jobs come first. Resources come from `backend/src/main/resources/skills/learning-resources.csv`,
a hand-kept list of official documentation and free courses. A skill with no entry shows "no curated resource yet": links are never
generated.

Keep the list healthy with:

```bash
python scripts/check-links.py           # prints broken links
python scripts/check-links.py --strict  # exits 1 if any link is broken (for a scheduled CI job)
```

Sites that refuse automated clients (HTTP 401, 403, 429) are reported as unverified rather than broken. A unit test checks that every
skill in the list exists in the skill dictionary and that every link is https.

## Interview preparation

For a job and a CV, `InterviewPrepService` builds:

- one question per important skill (required first, gaps before skills already shown): for a skill the CV shows, the question quotes
  the CV's own evidence and gives a Situation-Task-Action-Result frame; evidence that was only inferred is marked weak; for a gap, the
  question says plainly that the CV does not show it and the frame is about answering honestly;
- behavioural, motivation and "questions to ask them" items;
- revision topics with the learning resources above.

The frames are prompts. The tool never writes an answer for the user to claim. **Mock mode** goes through the questions one at a time,
lets the user type an answer, reveal the hints and rate themselves; what is typed stays in the page and is never sent or stored.
French and English are supported, following the interface language.

## Employer overview

Only facts the platform can show: how many open offers it has seen under that company name (spelling and legal forms are normalised,
so "Acme", "ACME" and "Acme GmbH" are one employer), on which sources and in which countries, since when, how many of them raised a
caution, and the user's own applications with that employer. Each listed offer links to its page.

There are no ratings, reviews, salary estimates or financial claims, because none could be cited. The page says to check the company
on its own website.

## Limits

- Alerts are as fresh as the automatic search interval; there is no real-time push.
- The warning rules are heuristics in two languages. They miss scams that avoid the listed wording.
- Statistics need volume to mean anything; the interface says so rather than hiding the numbers.
- The learning list covers the skills in the dictionary that have a well-known free resource, not all of them.
