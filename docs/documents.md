# Letters, application emails and HR contacts

## Principle: nothing is written that the CV does not say

Every letter and email starts from **facts**, short statements copied from the reviewed CV in its own wording: a relevant
experience bullet (with role, employer and period), a skill the offer asks for that the CV shows, the years spent in related
roles, a degree, a language the offer requires. The match report decides which facts relate to the offer (see
[scoring.md](scoring.md)). A document may only rely on those facts, and the interface shows them next to the text.

Two writers produce the text:

1. **Template writer (always available, no model needed).** French and English wording around the facts. It cannot add a claim,
   because every sentence is built from a fact or from fixed phrasing. Prose is plain on purpose: bullets are quoted as written
   in the CV.
2. **Language model writer (optional).** When a model is configured, it is asked to rewrite the paragraphs. It sees only the
   facts, the job title, the company name and the required skill names, never the full offer text. Its output is accepted only
   if every paragraph cites known fact ids **and** contains no number or capitalised name that is absent from the CV and the
   offer. One retry is allowed with the offending details listed; otherwise the template draft is used and the reason is shown
   (`ai-unavailable`, `ai-unusable`, `ai-ungrounded`). Greetings, closings and signatures are never written by the model.

If the CV shows nothing related to the offer, no document is written and the user is told why.

## Edits are the user's, and are checked

Editing saves a new version (history is kept, any version can be reopened or deleted). The saved text is checked against the CV
and the offer, and numbers or names that appear in neither are returned as warnings. Warnings never block saving: the user may
have good reasons, but they are told.

## Language

The default is the offer's language when supported, otherwise the user's interface language. The user can choose any supported
language. Detection is a function-word heuristic for French, English and German; German offers get a draft in a supported
language with a note, because German wording is not available yet. Adding a language means one `LetterLocale` bean
(`documents/FrenchLocale.java` is the model). French wording avoids guessing gender (`heureux(se)`).

## Tone, length, formality

| Option | Values | Effect |
|---|---|---|
| Tone | neutral, warm, direct | opening and closing sentences |
| Length | short, standard, detailed | 1, 2 or 3 experience paragraphs, plus years, education, language and desired-title paragraphs when detailed |
| Formality | formal, standard | salutation and closing formula |

The desired-title sentence appears only if the offer title resembles a title the user listed in their preferences.

## Exports

Letters: PDF (A4, Latin-1 characters, so all French and English accents), DOCX (a minimal Word package: text only, no macros),
TXT. Emails: `.eml` (an unsent draft with an `X-Unsent` header; subject and body are encoded and the optional recipient must be a
plain address, so nothing can inject headers) and TXT. The page also offers copy buttons and a `mailto:` link when a published
recipient was chosen. **Nothing is ever sent by this application.** There is no SMTP code.

## HR contact discovery

Contacts come only from text someone published:

1. **The offer text.** Any email address in the offer, and lines such as `Contact: Marie Dupont, Talent Acquisition`. Marked
   *verified* (published in the offer itself). `no-reply`, image-like and placeholder addresses are ignored.
2. **The employer's own pages**, when the offer lives on the employer's site (for example a page the user added by URL). The
   job page and up to two same-site careers or contact pages are read, honouring robots.txt. Recruiting addresses
   (`mailto:` links and addresses in paragraphs about hiring), and application links on the same site are kept. An address on
   the page's own domain is *verified*; an address on another domain is stored as *unverified*.

Offers on job boards and applicant-tracking hosts (Arbeitnow, Greenhouse, Lever, Workday, LinkedIn and others, see
`ContactDiscoveryService.BOARD_DOMAINS`) are **not** treated as the employer: only their text is read, so a board's own address
is never reported as the employer's.

**Addresses are never constructed** from a person's name, a company domain or a naming convention; tests assert this. When no
address is found the interface says "No publicly available HR email found" and shows the official application link.
Every contact stores its source URL and discovery date.
