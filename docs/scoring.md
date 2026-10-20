# How the match score works

The score answers one question: **how well does this posting fit this reviewed CV and these stated preferences?** It is
not a prediction of being hired, and the interface says so wherever a score appears.

## Method

Eight criteria each produce a value between 0 and 1, or **unknown**:

| Criterion | Default weight | What it compares | Unknown when |
|---|---|---|---|
| Required skills | 30 | Skills the ad marks as required, against the skills on the CV | the ad lists none |
| Text similarity | 20 | Shared vocabulary between the CV's work content and the ad (cosine over term frequencies; EN/FR/DE stop words removed) | either side has no text |
| Experience | 15 | Years the ad asks for against your years **in roles related to the offer**; otherwise seniority in the title against your level | the ad states neither, or the CV has no dated roles |
| Title | 10 | Overlap between the ad's title and your desired titles and last two job titles (gender markers and level words ignored) | no titles known |
| Preferred skills | 10 | Skills the ad calls optional, against the CV | the ad lists none |
| Languages | 5 | Languages the ad requires (a language name next to a cue such as "fluent", "courant", "required") against the CV | the ad requires none, or the CV lists no languages |
| Location | 5 | Work mode, cities and countries you prefer against the offer; willingness to relocate | you set no preference, or the ad says nothing |
| Contract and salary | 5 | Contract type and the top of the stated salary range against your minimum (same currency only) | neither can be compared |

```
score    = round( 100 × Σ w' × value  /  Σ w' )   over criteria that are known, w' = weight × confidence
coverage = round( 100 × Σ w' (known)  /  Σ weight (all) )
```

**Evidence-weighted skills.** A skill criterion built on very little evidence counts for less: its weight is multiplied by
`min(1, skills named / 3)`, and by a further factor down to 0.5 when those skills are only mentioned in passing rather than
marked required or preferred. An ad that names one skill in passing therefore cannot carry a score on its own. The
reduced weight is also reflected in `coverage`, and the report flags the criterion as "counts for less".

**Related experience only.** A role is related to an offer when its title or bullets name one of the offer's skills, or its
title shares a word with the offer's title. Ten years of Java do not satisfy "8 years of product management".

**Missing information is never a penalty.** An unknown criterion is dropped from both sums. `coverage` tells the reader how
much of the weighting could actually be evaluated, and the interface warns when it is low. If nothing is known the result is
"no score", not zero.

## Required, preferred, mentioned

An ad's skills are classified by section headings ("Requirements", "Nice to have", "Profil recherché", "Atouts") and cue words
on the same line ("must", "indispensable", "is a plus", "un plus"). A skill in the job title is required. A skill the ad
only mentions in passing is **inferred**: if the ad marks some skills as required, unmarked ones count as preferred; if it marks
none, they count as required. Inferred skills are labelled as such in the report.

## Blockers are shown, not folded into the score

- the offer appears closed;
- the ad says it cannot sponsor a visa and you said you need sponsorship;
- the stated salary ceiling is below your stated minimum (same currency);
- the role needs on-site presence away from your preferred places and you did not say you would relocate.

## Fairness and privacy

Only job-relevant evidence is read: skills, work content, titles, dates of experience, languages, and the preferences you set.
Name, email, phone, photo, age, gender or nationality are never inputs, and a test asserts that changing the identifying
fields leaves the result identical.

## Changing the weights

Set them in `application.yml` (or environment variables such as `APP_MATCHING_WEIGHTS_REQUIRED_SKILLS`):

```yaml
app:
  matching:
    weights:
      required-skills: 40
      text-similarity: 10
```

Any non-negative integers work; they are renormalised over the known criteria. Changing a weight invalidates stored results, so
they are recomputed on the next run.

## Limits

- Text similarity is lexical. A sentence-embedding model (planned behind the optional NLP worker) would capture paraphrases.
- Skill detection uses a curated French and English dictionary (`skills.csv`). Skills outside it are not scored; add them there.
- Experience years come from dated roles on the reviewed CV.
- Stated salaries are rare in ads, so contract and salary is often skipped.
