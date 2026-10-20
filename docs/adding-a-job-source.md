# Adding a job source

A source is one Spring bean implementing `ai.jobmatcher.jobs.JobSourceAdapter`. Nothing else needs to change: discovery,
storage, de-duplication, expiry, health monitoring and the UI pick it up automatically.

## Before writing code

Check the source's terms and technical access, in this order of preference:

1. An official API or feed meant for this use.
2. A public employer board API (Greenhouse-style).
3. Pages that publish schema.org `JobPosting` data and allow crawling in `robots.txt`.

Do not add a source that needs a login, a CAPTCHA, or evasion of rate limits or bot detection, or whose terms forbid
automated access. Record what you checked in `docs/THIRD_PARTY.md` (Data sources).

## The interface

```java
@Component
public class ExampleAdapter implements JobSourceAdapter {
    public String key()          { return "example"; }          // stored with every offer
    public String displayName()  { return "Example Jobs"; }
    public String termsUrl()     { return "https://example.org/api-terms"; }
    public boolean enabled()     { return true; }               // false when credentials are missing
    public String disabledReason() { return "Set EXAMPLE_API_KEY."; }
    public List<RawJob> search(SearchQuery q) { ... }
}
```

Rules for `search`:

- Use `SafeFetcher` for every request. It blocks private addresses, caps size and time, spaces requests per host, and can
  honour `robots.txt` (`checkRobots=true` for HTML pages; API endpoints skip it).
- Return `RawJob` values. Use `null` for anything the source does not state. Never guess a salary, a remote policy, a
  country or a contact.
- Convert HTML to text with `HtmlText.toText` so no markup is ever stored.
- Apply `q.matchesKeywords`, `q.matchesLocation` and `q.remoteOnly()` locally when the API cannot filter.
- Throw `SafeFetcher.FetchException` with a message that is safe to show the user. Failures are recorded in the source's
  health row and shown on the Jobs page; one failing source never blocks the others.
- Pick a stable `externalId` (the source's own id). Re-seeing it updates the offer instead of duplicating it.

## Tests

Put a recorded, anonymised response in `backend/src/test/resources/fixtures/` and test your `map` function against it
(see `JobParsingTest`). Keep any real-network test behind `RUN_LIVE_TESTS=true` (see `LiveSourcesTest`).

## What you get for free

- Cross-source duplicate detection (same title, company and city).
- Expiry when a deadline passes, a page returns 404/410, or the offer is not seen for 30 days.
- Per-source health: last success, consecutive failures, last error, active offer count.
