# Security

## Reporting a vulnerability

Please report security problems privately to the maintainers (use the repository's private vulnerability reporting, or email the
address in the repository profile) instead of opening a public issue. Include steps to reproduce and the version. You will get an
answer within a few days.

## Threat model in one page

JobMatcher AI stores CVs, which are personal data, and fetches and parses untrusted content from the internet.

| Asset or boundary | Threat | Control | Where tested |
|---|---|---|---|
| Accounts | credential stuffing, brute force | Argon2id hashing, 12 character minimum, per-address and per-account rate limits, generic login error, session id rotation on login | `ApiIntegrationTest`, `SecurityIntegrationTest`, `CommonUtilitiesTest` |
| Sessions | CSRF, fixation, theft | HttpOnly SameSite=Lax cookie, CSRF double-submit token on every state change, no tokens in script-readable storage | `SecurityIntegrationTest` |
| Other users' data | broken access control | every query is scoped by the authenticated user id; foreign ids answer 404 | `*IntegrationTest` (each module has an isolation test) |
| CV text and notes at rest | database theft or backup leak | AES-256-GCM per field with a key kept outside the database | `ApplicationsIntegrationTest` reads raw columns |
| Uploaded files | malware, zip bombs, parser exploits | type decided from magic bytes, size and entry limits, macros refused, parse timeout and text cap, file never stored | `UploadValidatorTest`, `TextExtractorTest` |
| Outbound fetching | SSRF to internal services | one fetcher: public addresses only, ports 80 and 443, every redirect re-checked, size and time caps, robots.txt | `UrlGuardTest`, `SafeFetcherTest` |
| Job ads and CVs | stored XSS | only plain text is stored; the web app renders text, never HTML; strict CSP | `PromptInjectionTest`, `HtmlText` tests |
| Language model | prompt injection, invented claims | the model has no tools and no network; untrusted text is delimited and the delimiter defused; output must cite known facts and pass a grounding check, otherwise it is discarded | `PromptInjectionTest`, `DocumentComposerTest` |
| Dependencies | known vulnerabilities | `scripts/audit-deps.py` checks every package against OSV; CI fails on high severity | CI |
| Logs | personal data leaks | the `audit` logger holds user ids and truncated addresses only | `CommonUtilitiesTest` |

## Known limits

- **DNS rebinding.** The fetcher validates the resolved addresses before connecting. A hostile DNS server that changes its answer
  between the check and the connection is a residual risk. Run the API without access to internal networks if you handle
  untrusted submitted URLs.
- **Single instance.** Rate limits and the job scheduler are in memory. Add a shared store and a scheduler lock before running
  more than one API replica.
- **Registration is open.** Anyone who can reach the site can create an account; restrict access at the proxy if needed.
  Registering an existing email answers 409 (a deliberate usability trade-off that reveals whether an address is registered).
- **API documentation is public.** `/api/docs` is reachable without sign-in; disable it with `springdoc.api-docs.enabled=false`
  and `springdoc.swagger-ui.enabled=false` if you prefer.
- **The model is a text generator, not a safety boundary.** Generated text is always shown to the user for review and editing
  before use; nothing is sent automatically.
- **No OCR and no antivirus.** Scanned documents are refused; uploads are parsed in-process after strict validation.

## Supported versions

Only the latest release receives security fixes while the project is pre-1.0.
