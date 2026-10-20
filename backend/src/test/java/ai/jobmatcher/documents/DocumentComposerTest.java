package ai.jobmatcher.documents;

import static ai.jobmatcher.matching.MatchFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import ai.jobmatcher.ai.AiUnavailableException;
import ai.jobmatcher.ai.TextGenerator;
import ai.jobmatcher.cv.ParsedCv;
import ai.jobmatcher.matching.MatchReport;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DocumentComposerTest {

    private final EvidenceSelector selector = new EvidenceSelector();
    private final LetterComposer composer = new LetterComposer();
    private final EnglishLocale en = new EnglishLocale();
    private final FrenchLocale fr = new FrenchLocale();

    private EvidenceSelector.Context context() {
        ParsedCv cv = backendCv();
        MatchReport report = scorer().score(cv, noPrefs(), job("Senior Java Developer", SENIOR_JAVA_AD), true);
        return selector.select(cv, report, noPrefs(), "Senior Java Developer", "Acme GmbH", SENIOR_JAVA_AD, BACKEND_CV, "Jane Doe");
    }

    private static LetterOptions options(LetterOptions.Length len, LetterOptions.Formality f, LetterOptions.Tone t) {
        return new LetterOptions("en", t, len, f, false);
    }

    // ---- evidence ----

    @Test
    void evidenceComesFromTheCvInItsOwnWords() {
        EvidenceSelector.Context ctx = context();

        assertThat(ctx.facts()).isNotEmpty();
        assertThat(ctx.ofType(Fact.Type.EXPERIENCE)).isNotEmpty();
        assertThat(ctx.ofType(Fact.Type.EXPERIENCE).get(0).text()).isIn(
                "Designed REST APIs with Spring Boot used by 12 internal teams",
                "Reduced report generation time by 40% by tuning PostgreSQL queries");
        assertThat(ctx.skillNames()).contains("Java", "Spring Boot", "PostgreSQL");
        assertThat(ctx.ofType(Fact.Type.LANGUAGE)).extracting(Fact::text).containsExactly("English (C1)");
        assertThat(ctx.facts()).extracting(Fact::id).doesNotHaveDuplicates().allMatch(id -> id.matches("F\\d+"));
        // every fact is checkable against the CV
        ctx.ofType(Fact.Type.EXPERIENCE).forEach(f -> assertThat(BACKEND_CV).contains(f.text()));
    }

    // ---- template letter ----

    @Test
    void englishLetterUsesOnlyCvFactsAndIsGrounded() {
        EvidenceSelector.Context ctx = context();

        LetterDraft d = composer.compose(ctx, options(LetterOptions.Length.DETAILED, LetterOptions.Formality.FORMAL, LetterOptions.Tone.NEUTRAL), en);

        String text = d.plainText();
        assertThat(d.salutation()).isEqualTo("Dear Sir or Madam,");
        assertThat(text).contains("Senior Java Developer position at Acme GmbH").contains("Designed REST APIs with Spring Boot").contains("Yours faithfully,")
                .endsWith("Jane Doe");
        assertThat(GroundingValidator.unsupported(text, ctx.corpus())).isEmpty();
        assertThat(d.generator()).isEqualTo("template");
        d.paragraphs().forEach(p -> p.factIds().forEach(id -> assertThat(ctx.facts()).extracting(Fact::id).contains(id)));
    }

    @Test
    void frenchLetterIsWrittenInFrenchWithoutGuessingGender() {
        EvidenceSelector.Context ctx = context();

        String text = composer.compose(ctx, new LetterOptions("fr", LetterOptions.Tone.WARM, LetterOptions.Length.STANDARD, LetterOptions.Formality.FORMAL, false), fr)
                .plainText();

        assertThat(text).startsWith("Madame, Monsieur,").contains("poste de Senior Java Developer au sein de Acme GmbH").contains("J'ai occupé le poste de")
                .contains("agréer, Madame, Monsieur");
        assertThat(text).contains("heureux(se)");
        assertThat(GroundingValidator.unsupported(text, ctx.corpus())).isEmpty();
    }

    @Test
    void lengthToneAndFormalityChangeTheLetter() {
        EvidenceSelector.Context ctx = context();

        String shortText = composer.compose(ctx, options(LetterOptions.Length.SHORT, LetterOptions.Formality.STANDARD, LetterOptions.Tone.DIRECT), en).plainText();
        String longText = composer.compose(ctx, options(LetterOptions.Length.DETAILED, LetterOptions.Formality.FORMAL, LetterOptions.Tone.WARM), en).plainText();

        assertThat(shortText.length()).isLessThan(longText.length());
        assertThat(shortText).startsWith("Hello,").contains("Kind regards,").contains("I am applying for");
        assertThat(longText).contains("I was glad to see").contains("My education:");
        assertThat(shortText.split("In that role").length - 1).isEqualTo(1);
    }

    @Test
    void companyIsOnlyNamedWhenTheOfferStatesIt() {
        ParsedCv cv = backendCv();
        MatchReport report = scorer().score(cv, noPrefs(), job("Java Developer", SENIOR_JAVA_AD), true);
        EvidenceSelector.Context ctx = selector.select(cv, report, noPrefs(), "Java Developer", null, SENIOR_JAVA_AD, BACKEND_CV, "Jane Doe");

        String text = composer.compose(ctx, options(LetterOptions.Length.SHORT, LetterOptions.Formality.FORMAL, LetterOptions.Tone.NEUTRAL), en).plainText();

        assertThat(text).contains("the Java Developer position you have advertised").doesNotContain(" at null");
    }

    @Test
    void desiredTitleIsMentionedOnlyWhenItMatchesTheOffer() {
        ParsedCv cv = backendCv();
        var prefs = prefs(List.of("Senior Java Developer"), null, List.of(), List.of(), List.of(), List.of(), false, null, null, null);
        MatchReport report = scorer().score(cv, prefs, job("Senior Java Developer", SENIOR_JAVA_AD), true);
        EvidenceSelector.Context withTitle = selector.select(cv, report, prefs, "Senior Java Developer", "Acme", SENIOR_JAVA_AD, BACKEND_CV, "Jane Doe");
        EvidenceSelector.Context without = context();

        LetterOptions detailed = options(LetterOptions.Length.DETAILED, LetterOptions.Formality.FORMAL, LetterOptions.Tone.NEUTRAL);
        assertThat(composer.compose(withTitle, detailed, en).plainText()).contains("matches the direction I am looking for: Senior Java Developer");
        assertThat(composer.compose(without, detailed, en).plainText()).doesNotContain("direction I am looking for");
    }

    // ---- grounding ----

    @Test
    void groundingFlagsInventedNumbersAndNames() {
        String corpus = (BACKEND_CV + " Acme GmbH Senior Java Developer").toLowerCase();

        assertThat(GroundingValidator.unsupported("I reduced costs by 95% at Globex last year.", corpus)).contains("95", "Globex");
        assertThat(GroundingValidator.unsupported("I cut report time by 40% using PostgreSQL at Northwind Labs.", corpus)).isEmpty();
        assertThat(GroundingValidator.unsupported("I led a team of 50 engineers.", corpus)).contains("50");
        assertThat(GroundingValidator.unsupported("Dear Sir or Madam, I speak English and French.", corpus)).isEmpty();
    }

    // ---- AI writer ----

    private static TextGenerator stub(String... replies) {
        int[] i = {0};
        return new TextGenerator() {
            public String generate(String s, String u, boolean j) {
                return replies[Math.min(i[0]++, replies.length - 1)];
            }
            public String name() { return "stub"; }
            public AiHealth health() { return new AiHealth(true, "stub"); }
        };
    }

    @Test
    void aiParagraphsAreAcceptedOnlyWhenGroundedAndCited() {
        EvidenceSelector.Context ctx = context();
        String id = ctx.ofType(Fact.Type.EXPERIENCE).get(0).id();
        String good = "{\"paragraphs\":[{\"text\":\"At Northwind Labs I designed REST APIs with Spring Boot that 12 internal teams rely on.\",\"facts\":[\"" + id + "\"]}]}";

        LetterAiWriter.Outcome out = new LetterAiWriter(stub(good), JsonMapper.builder().build()).write(ctx,
                options(LetterOptions.Length.STANDARD, LetterOptions.Formality.FORMAL, LetterOptions.Tone.NEUTRAL), en, List.of("Java"));

        assertThat(out.note()).isEqualTo("ai");
        assertThat(out.draft()).isPresent();
        assertThat(out.draft().get().generator()).isEqualTo("ai:stub");
        assertThat(out.draft().get().salutation()).isEqualTo("Dear Sir or Madam,"); // fixed text, never written by the model
    }

    @Test
    void aiOutputWithInventedDetailsIsRetriedThenRejected() {
        EvidenceSelector.Context ctx = context();
        String id = ctx.ofType(Fact.Type.EXPERIENCE).get(0).id();
        String invented = "{\"paragraphs\":[{\"text\":\"At Northwind Labs I increased revenue by 95% and won the Globex contract.\",\"facts\":[\"" + id + "\"]}]}";
        int[] calls = {0};
        TextGenerator g = new TextGenerator() {
            public String generate(String s, String u, boolean j) {
                calls[0]++;
                return invented;
            }
            public String name() { return "stub"; }
            public AiHealth health() { return new AiHealth(true, "stub"); }
        };

        LetterAiWriter.Outcome out = new LetterAiWriter(g, JsonMapper.builder().build()).write(ctx,
                options(LetterOptions.Length.STANDARD, LetterOptions.Formality.FORMAL, LetterOptions.Tone.NEUTRAL), en, List.of());

        assertThat(out.draft()).isEmpty();
        assertThat(out.note()).isEqualTo("ai-ungrounded");
        assertThat(calls[0]).isEqualTo(2);
    }

    @Test
    void aiOutputCitingUnknownFactsOrBadJsonIsRejectedAndUnavailableIsReported() {
        EvidenceSelector.Context ctx = context();
        LetterOptions o = options(LetterOptions.Length.STANDARD, LetterOptions.Formality.FORMAL, LetterOptions.Tone.NEUTRAL);
        JsonMapper json = JsonMapper.builder().build();

        String unknownFact = "{\"paragraphs\":[{\"text\":\"I designed REST APIs with Spring Boot for the teams.\",\"facts\":[\"F99\"]}]}";
        assertThat(new LetterAiWriter(stub(unknownFact), json).write(ctx, o, en, List.of()).note()).isEqualTo("ai-unusable");
        assertThat(new LetterAiWriter(stub("Sure! Here is your letter"), json).write(ctx, o, en, List.of()).note()).isEqualTo("ai-unusable");
        assertThat(new LetterAiWriter(stub("{}"), json).write(ctx, o, en, List.of()).note()).isEqualTo("ai-unusable");

        TextGenerator down = new TextGenerator() {
            public String generate(String s, String u, boolean j) { throw new AiUnavailableException("down"); }
            public String name() { return "down"; }
            public AiHealth health() { return new AiHealth(false, "down"); }
        };
        assertThat(new LetterAiWriter(down, json).write(ctx, o, en, List.of()).note()).isEqualTo("ai-unavailable");
    }

    // ---- email ----

    @Test
    void emailHasSubjectGreetingRoleEvidenceAttachmentsAndClosing() {
        EvidenceSelector.Context ctx = context();

        EmailComposer.Email e = new EmailComposer().compose(ctx, options(LetterOptions.Length.STANDARD, LetterOptions.Formality.FORMAL, LetterOptions.Tone.NEUTRAL),
                en, true, null);

        assertThat(e.subject()).isEqualTo("Application: Senior Java Developer (Acme GmbH)");
        assertThat(e.body()).startsWith("Dear Hiring Team,").contains("Senior Java Developer position at Acme GmbH").contains("What matches your posting:")
                .contains("My CV and cover letter are attached.").contains("Yours sincerely,").contains("Jane Doe").contains("jane.doe@example.org");
        assertThat(e.body().lines().filter(l -> l.startsWith("- ")).count()).isBetween(2L, 3L);
        assertThat(e.body()).contains("- Skills from your posting that appear on my CV: Java, Spring Boot, PostgreSQL");
        assertThat(e.body()).doesNotContain("- -"); // no bullet glyph from the CV leaks into the message
        assertThat(GroundingValidator.unsupported(e.body(), ctx.corpus())).isEmpty();
        assertThat(e.factIds()).isNotEmpty();
    }

    @Test
    void emailInFrenchAddressesAPublishedContactNameAndOmitsTheLetterWhenAsked() {
        EvidenceSelector.Context ctx = context();

        EmailComposer.Email e = new EmailComposer().compose(ctx, new LetterOptions("fr", LetterOptions.Tone.NEUTRAL, LetterOptions.Length.STANDARD,
                LetterOptions.Formality.STANDARD, false), fr, false, "Marie Dupont");

        assertThat(e.subject()).startsWith("Candidature : Senior Java Developer");
        assertThat(e.body()).startsWith("Bonjour Marie Dupont,").contains("Vous trouverez ci-joint mon CV.").doesNotContain("lettre de motivation").endsWith("+33 6 12 34 56 78");
    }

    @Test
    void nothingIsWrittenWhenTheCvHasNoRelevantFacts() {
        ParsedCv empty = ParsedCv.empty();
        MatchReport report = scorer().score(empty, noPrefs(), job("Astronaut", "Requirements\n- Space travel"), true);

        EvidenceSelector.Context ctx = selector.select(empty, report, noPrefs(), "Astronaut", null, "x", "", "Jane Doe");

        assertThat(ctx.facts()).isEmpty();
    }
}
