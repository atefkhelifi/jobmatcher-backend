package ai.jobmatcher.matching;

import static ai.jobmatcher.matching.MatchFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Collectors;

import ai.jobmatcher.cv.ParsedCv;
import ai.jobmatcher.jobs.RemoteMode;

import org.junit.jupiter.api.Test;

class MatchScorerTest {

    private final MatchScorer scorer = scorer();

    private static MatchReport.ComponentResult comp(MatchReport r, String key) {
        return r.components().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    private static List<String> names(List<MatchReport.SkillResult> skills, String level, boolean matched) {
        return skills.stream().filter(s -> s.level().equals(level) && s.matched() == matched).map(MatchReport.SkillResult::skill).toList();
    }

    // ---- skill extraction ----

    @Test
    void separatesRequiredFromPreferredUsingHeadingsAndCues() {
        JobSkillExtractor x = new JobSkillExtractor(CATALOG);

        var skills = x.extract("Backend Developer", SENIOR_JAVA_AD);
        var byName = skills.stream().collect(Collectors.toMap(JobSkillExtractor.JobSkill::skill, JobSkillExtractor.JobSkill::level));

        assertThat(byName.get("Java")).isEqualTo(JobSkillExtractor.Level.REQUIRED);
        assertThat(byName.get("Spring Boot")).isEqualTo(JobSkillExtractor.Level.REQUIRED);
        assertThat(byName.get("PostgreSQL")).isEqualTo(JobSkillExtractor.Level.REQUIRED);
        assertThat(byName.get("Kafka")).isEqualTo(JobSkillExtractor.Level.PREFERRED);
        assertThat(byName.get("Kubernetes")).isEqualTo(JobSkillExtractor.Level.PREFERRED);
    }

    @Test
    void understandsFrenchHeadingsAndCues() {
        String ad = """
                Vos missions
                Concevoir des API avec Docker.

                Profil recherché
                - Maîtrise de Java et de Spring Boot
                - Angular est un plus
                """;

        var byName = new JobSkillExtractor(CATALOG).extract("Développeur", ad).stream()
                .collect(Collectors.toMap(JobSkillExtractor.JobSkill::skill, JobSkillExtractor.JobSkill::level));

        assertThat(byName.get("Java")).isEqualTo(JobSkillExtractor.Level.REQUIRED);
        assertThat(byName.get("Spring Boot")).isEqualTo(JobSkillExtractor.Level.REQUIRED);
        assertThat(byName.get("Angular")).isEqualTo(JobSkillExtractor.Level.PREFERRED);
        assertThat(byName.get("Docker")).isEqualTo(JobSkillExtractor.Level.MENTIONED); // under "Vos missions"
    }

    @Test
    void skillNamedInTheTitleIsRequiredAndUnstructuredAdsAreMarkedInferred() {
        var skills = new JobSkillExtractor(CATALOG).extract("Java Developer", "We use Docker and Kafka every day in a friendly team.");
        var byName = skills.stream().collect(Collectors.toMap(JobSkillExtractor.JobSkill::skill, JobSkillExtractor.JobSkill::level));

        assertThat(byName.get("Java")).isEqualTo(JobSkillExtractor.Level.REQUIRED);
        assertThat(byName.get("Docker")).isEqualTo(JobSkillExtractor.Level.MENTIONED);

        MatchReport r = scorer.score(backendCv(), noPrefs(), job("Java Developer", "We use Docker and Kafka every day in a friendly team."), true);
        MatchReport.SkillResult docker = r.skills().stream().filter(s -> s.skill().equals("Docker")).findFirst().orElseThrow();
        assertThat(docker.inferred()).isTrue();
        assertThat(docker.level()).isEqualTo("PREFERRED"); // the ad does name a required skill, so unmarked ones count as preferred
    }

    @Test
    void whenNoSkillIsMarkedRequiredMentionedSkillsCountAsRequired() {
        MatchReport r = scorer.score(backendCv(), noPrefs(), job("Engineer", "Our stack is Docker and Kafka."), true);

        assertThat(names(r.skills(), "REQUIRED", true)).containsExactly("Docker");
        assertThat(names(r.skills(), "REQUIRED", false)).containsExactly("Kafka");
        assertThat(r.skills()).allMatch(MatchReport.SkillResult::inferred);
    }

    @Test
    void parsesExperienceRequirementsNextToTheWordExperience() {
        assertThat(MatchScorer.requiredYears("We need 5+ years of experience with Java.")).isEqualTo(5);
        assertThat(MatchScorer.requiredYears("Minimum 3 ans d'expérience en développement.")).isEqualTo(3);
        assertThat(MatchScorer.requiredYears("We are a company with 10 years of history in logistics.")).isNull();
        assertThat(MatchScorer.requiredYears("No numbers here")).isNull();
    }

    // ---- scoring ----

    @Test
    void strongMatchScoresHighAndExplainsWhy() {
        MatchReport r = scorer.score(backendCv(), noPrefs(), job("Senior Java Developer", SENIOR_JAVA_AD), true);

        assertThat(r.score()).isGreaterThanOrEqualTo(80);
        assertThat(names(r.skills(), "REQUIRED", true)).containsExactlyInAnyOrder("Java", "Spring Boot", "PostgreSQL");
        assertThat(names(r.skills(), "PREFERRED", true)).containsExactly("Kubernetes");
        assertThat(names(r.skills(), "PREFERRED", false)).containsExactly("Kafka");
        assertThat(comp(r, "requiredSkills").score()).isEqualTo(1.0);
        assertThat(comp(r, "preferredSkills").score()).isEqualTo(0.5);
        assertThat(comp(r, "languages").score()).isEqualTo(1.0); // "Fluent English" and the CV states English
        assertThat(r.strengths()).extracting(MatchReport.Note::code).contains("strength.requiredSkills", "strength.preferredSkills");
        assertThat(r.gaps()).extracting(MatchReport.Note::code).contains("gap.preferredSkills").doesNotContain("gap.requiredSkills");
        assertThat(r.learningPriorities()).extracting(MatchReport.LearningItem::skill).containsExactly("Kafka");
        assertThat(r.relevantExperience()).isNotEmpty();
        assertThat(r.relevantExperience().get(0).title()).isEqualTo("Senior Java Developer");
        assertThat(r.relevantExperience().get(0).quote()).contains("Spring Boot");
    }

    @Test
    void unrelatedJobScoresLowAndListsMissingRequiredSkills() {
        MatchReport r = scorer.score(backendCv(), noPrefs(), job("Data Scientist", DATA_SCIENCE_AD), true);

        assertThat(r.score()).isLessThan(40);
        assertThat(names(r.skills(), "REQUIRED", false)).contains("Python", "Pandas", "Spark");
        assertThat(r.gaps()).extracting(MatchReport.Note::code).contains("gap.requiredSkills");
        assertThat(r.learningPriorities()).isNotEmpty().allMatch(l -> l.level().equals("REQUIRED"));
        assertThat(r.cvAdjustments()).extracting(MatchReport.Note::code).contains("adjust.missingRequired");
    }

    @Test
    void missingRequiredSkillsCostMoreThanMissingPreferredOnes() {
        String twoRequired = "Requirements\n- Java\n- Kafka\n";
        String twoPreferred = "Requirements\n- Java\n\nNice to have\n- Kafka\n";

        int hardGap = scorer.score(backendCv(), noPrefs(), job("Engineer", twoRequired), true).score();
        int softGap = scorer.score(backendCv(), noPrefs(), job("Engineer", twoPreferred), true).score();

        assertThat(softGap).isGreaterThan(hardGap);
    }

    @Test
    void scoreIsTheWeightedMeanOfOnlyTheCriteriaThatCouldBeEvaluated() {
        MatchReport r = scorer.score(backendCv(), noPrefs(), job("Senior Java Developer", SENIOR_JAVA_AD), true);

        double known = 0;
        double sum = 0;
        double all = 0;
        for (var c : r.components()) {
            all += c.weight();
            if (c.known()) {
                known += c.effectiveWeight();
                sum += c.effectiveWeight() * c.score();
            }
        }
        assertThat(r.score()).isEqualTo((int) Math.round(100 * sum / known));
        assertThat(r.coverage()).isEqualTo((int) Math.round(100 * known / all));
        assertThat(comp(r, "contractSalary").known()).isFalse(); // the ad states nothing, so it is left out
        assertThat(comp(r, "location").known()).isFalse();
        assertThat(r.coverage()).isLessThan(100);
    }

    @Test
    void anUnstatedSalaryIsNeutralWhileAStatedLowSalaryHurts() {
        var prefs = prefs(List.of(), null, List.of(), List.of(), List.of(), List.of(), false, null, 60000, "EUR");
        String ad = SENIOR_JAVA_AD;

        MatchReport unstated = scorer.score(backendCv(), prefs, job("Senior Java Developer", ad), true);
        MatchReport low = scorer.score(backendCv(), prefs,
                job("Senior Java Developer", ad, null, null, RemoteMode.UNKNOWN, null, 30000, "EUR"), true);
        MatchReport fine = scorer.score(backendCv(), prefs,
                job("Senior Java Developer", ad, null, null, RemoteMode.UNKNOWN, null, 70000, "EUR"), true);

        assertThat(comp(unstated, "contractSalary").known()).isFalse();
        assertThat(unstated.score()).isGreaterThan(low.score());
        assertThat(fine.score()).isGreaterThanOrEqualTo(unstated.score() - 1);
        assertThat(low.blockers()).extracting(MatchReport.Note::code).containsExactly("blocker.salaryBelow");
        assertThat(unstated.blockers()).isEmpty();
        assertThat(fine.blockers()).isEmpty();
    }

    @Test
    void protectedOrIdentifyingFieldsNeverChangeTheResult() {
        ParsedCv original = backendCv();
        ParsedCv changed = new ParsedCv("Completely Different Name", "other@example.com", "+1 555 0100", original.summary(), original.skills(),
                original.experience(), original.education(), original.languages(), original.certifications(), original.projects(),
                original.yearsOfExperience());

        MatchReport a = scorer.score(original, noPrefs(), job("Senior Java Developer", SENIOR_JAVA_AD), true);
        MatchReport b = scorer.score(changed, noPrefs(), job("Senior Java Developer", SENIOR_JAVA_AD), true);

        assertThat(b).isEqualTo(a);
    }

    @Test
    void experienceUsesStatedYearsThenFallsBackToSeniorityOfTheTitle() {
        ParsedCv cv = backendCv(); // about 10 years
        MatchReport years = scorer.score(cv, noPrefs(), job("Developer", "We need 15 years of experience in Java."), true);
        assertThat(comp(years, "experience").score()).isBetween(0.5, 0.8);
        assertThat(years.gaps()).extracting(MatchReport.Note::code).contains("gap.experience");

        MatchReport junior = scorer.score(cv, noPrefs(), job("Junior Java Developer", "Java team."), true);
        assertThat(comp(junior, "experience").score()).isLessThan(1.0); // overqualified for a junior post
        // About ten years of dated experience reads as lead level, so a senior post is a slight step down and a lead post fits.
        MatchReport senior = scorer.score(cv, noPrefs(), job("Senior Java Developer", "Java team."), true);
        assertThat(comp(senior, "experience").score()).isEqualTo(0.8);
        MatchReport lead = scorer.score(cv, noPrefs(), job("Lead Java Developer", "Java team."), true);
        assertThat(comp(lead, "experience").score()).isEqualTo(1.0);

        MatchReport none = scorer.score(cv, noPrefs(), job("Java Developer", "Java team."), true);
        assertThat(comp(none, "experience").known()).isFalse();
    }

    @Test
    void titleRelevanceComparesAgainstDesiredAndPastTitlesIgnoringGenderMarkersAndLevel() {
        var prefs = prefs(List.of("Backend Engineer"), null, List.of(), List.of(), List.of(), List.of(), false, null, null, null);

        MatchReport same = scorer.score(backendCv(), noPrefs(), job("Java Developer (m/f/d)", "x"), true);
        MatchReport other = scorer.score(backendCv(), noPrefs(), job("Marketing Manager", "x"), true);
        MatchReport desired = scorer.score(backendCv(), prefs, job("Backend Engineer", "x"), true);

        assertThat(comp(same, "title").score()).isEqualTo(1.0);
        assertThat(comp(other, "title").score()).isEqualTo(0.0);
        assertThat(comp(desired, "title").score()).isEqualTo(1.0);
    }

    @Test
    void languageRequirementsAreCheckedAgainstTheCv() {
        MatchReport german = scorer.score(backendCv(), noPrefs(), job("Developer", "Fluent German is required for this role."), true);
        assertThat(comp(german, "languages").score()).isEqualTo(0.0);
        assertThat(german.gaps()).extracting(MatchReport.Note::code).contains("gap.languages");

        MatchReport french = scorer.score(backendCv(), noPrefs(), job("Développeur", "Français courant exigé. Anglais lu et écrit apprécié."), true);
        assertThat(comp(french, "languages").known()).isTrue();
        assertThat(comp(french, "languages").score()).isEqualTo(1.0);

        MatchReport mentionOnly = scorer.score(backendCv(), noPrefs(), job("Developer", "Our offices are in Berlin and the meetings are in English."), true);
        assertThat(comp(mentionOnly, "languages").known()).isFalse();
    }

    @Test
    void locationFollowsPreferencesAndRelocationWillingness() {
        var remoteOnly = prefs(List.of(), null, List.of(), List.of("remote"), List.of(), List.of(), false, null, null, null);
        var lyonOnly = prefs(List.of(), null, List.of(), List.of(), List.of("FR"), List.of("Lyon"), false, null, null, null);
        var lyonMove = prefs(List.of(), null, List.of(), List.of(), List.of("FR"), List.of("Lyon"), true, null, null, null);

        MatchReport remote = scorer.score(backendCv(), remoteOnly, job("Dev", "x", "Berlin", "DE", RemoteMode.REMOTE, null, null, null), true);
        assertThat(comp(remote, "location").score()).isEqualTo(1.0);

        MatchReport inLyon = scorer.score(backendCv(), lyonOnly, job("Dev", "x", "Lyon, ARA", "FR", RemoteMode.ONSITE, null, null, null), true);
        assertThat(comp(inLyon, "location").score()).isEqualTo(1.0);

        MatchReport berlin = scorer.score(backendCv(), lyonOnly, job("Dev", "x", "Berlin", "DE", RemoteMode.ONSITE, null, null, null), true);
        assertThat(comp(berlin, "location").score()).isEqualTo(0.2);
        assertThat(berlin.blockers()).extracting(MatchReport.Note::code).contains("blocker.relocation");

        MatchReport berlinMove = scorer.score(backendCv(), lyonMove, job("Dev", "x", "Berlin", "DE", RemoteMode.ONSITE, null, null, null), true);
        assertThat(comp(berlinMove, "location").score()).isEqualTo(0.6);
        assertThat(berlinMove.blockers()).isEmpty();

        MatchReport unknown = scorer.score(backendCv(), lyonOnly, job("Dev", "x"), true);
        assertThat(comp(unknown, "location").known()).isFalse();
    }

    @Test
    void contractTypeIsOnlyJudgedWhenTheAdStatesOne() {
        var perm = prefs(List.of(), null, List.of("permanent"), List.of(), List.of(), List.of(), false, null, null, null);

        MatchReport cdi = scorer.score(backendCv(), perm, job("Dev", "x", null, null, RemoteMode.UNKNOWN, "CDI", null, null), true);
        MatchReport freelance = scorer.score(backendCv(), perm, job("Dev", "x", null, null, RemoteMode.UNKNOWN, "freelance", null, null), true);
        MatchReport fullTime = scorer.score(backendCv(), perm, job("Dev", "x", null, null, RemoteMode.UNKNOWN, "full_time", null, null), true);

        assertThat(comp(cdi, "contractSalary").score()).isEqualTo(1.0);
        assertThat(comp(freelance, "contractSalary").score()).isEqualTo(0.2);
        assertThat(comp(fullTime, "contractSalary").known()).isFalse(); // "full time" does not say permanent
    }

    @Test
    void visaBlockerAppearsOnlyForCandidatesWhoNeedSponsorship() {
        String ad = "Requirements\n- Java\n\nUnfortunately we cannot offer visa sponsorship for this role.";
        var needs = prefs(List.of(), null, List.of(), List.of(), List.of(), List.of(), false, true, null, null);
        var doesNot = prefs(List.of(), null, List.of(), List.of(), List.of(), List.of(), false, false, null, null);

        assertThat(scorer.score(backendCv(), needs, job("Dev", ad), true).blockers()).extracting(MatchReport.Note::code).containsExactly("blocker.visa");
        assertThat(scorer.score(backendCv(), doesNot, job("Dev", ad), true).blockers()).isEmpty();
        assertThat(scorer.score(backendCv(), noPrefs(), job("Dev", ad), true).blockers()).isEmpty();
    }

    @Test
    void closedOffersCarryABlocker() {
        var closed = new MatchScorer.JobFacts("Dev", "Requirements\n- Java", null, null, RemoteMode.UNKNOWN, null, null, null, null, true);

        assertThat(scorer.score(backendCv(), noPrefs(), closed, true).blockers()).extracting(MatchReport.Note::code).contains("blocker.closed");
    }

    @Test
    void configuredWeightsChangeTheOutcome() {
        MatchScorer onlyRequired = scorer(new MatchWeights(100, 0, 0, 0, 0, 0, 0, 0));
        MatchScorer onlyPreferred = scorer(new MatchWeights(0, 0, 0, 0, 100, 0, 0, 0));

        MatchReport a = onlyRequired.score(backendCv(), noPrefs(), job("Senior Java Developer", SENIOR_JAVA_AD), true);
        MatchReport b = onlyPreferred.score(backendCv(), noPrefs(), job("Senior Java Developer", SENIOR_JAVA_AD), true);

        assertThat(a.score()).isEqualTo(100); // all three required skills are on the CV
        assertThat(b.score()).isEqualTo(50);  // one of the two preferred skills is
    }

    @Test
    void invalidWeightsAreRejected() {
        assertThatThrownBy(() -> new MatchWeights(-1, 0, 0, 0, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchWeights(0, 0, 0, 0, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooLittleKnownInformationGivesNoScoreInsteadOfAZero() {
        MatchReport r = scorer.score(ParsedCv.empty(), noPrefs(), job("Role", "A short note with no skills at all."), false);

        assertThat(r.insufficientData()).isTrue();
        assertThat(r.score()).isNull();
        assertThat(r.coverage()).isZero();
        assertThat(r.cvAdjustments()).extracting(MatchReport.Note::code).contains("adjust.review");
    }

    @Test
    void skillsListedButNeverShownInUseGetAnAdjustmentHint() {
        MatchReport r = scorer.score(backendCv(), noPrefs(), job("Senior Java Developer", SENIOR_JAVA_AD), true);

        // Java and PostgreSQL appear in experience bullets; Kubernetes is preferred, so only required skills are hinted.
        assertThat(r.cvAdjustments()).extracting(m -> m.code()).doesNotContain("adjust.missingRequired");
        MatchReport onlyListed = scorer.score(PARSER_ONLY_LISTED(), noPrefs(), job("Developer", "Requirements\n- Docker\n- Git"), true);
        assertThat(onlyListed.cvAdjustments()).extracting(m -> m.code()).contains("adjust.showInUse");
    }

    private static ParsedCv PARSER_ONLY_LISTED() {
        return PARSER.parse("Sam Example\nsam@example.org\n\nSkills\nDocker, Git, Linux\n");
    }

    @Test
    void aSinglePassingSkillMentionCannotCarryAnUnrelatedRole() {
        // Real-world shape: a product director ad that mentions Kubernetes once and asks for 8 years of experience.
        String ad = """
                We lead product strategy for our platform.
                You bring 8+ years of experience in product management.
                Our platform runs on Kubernetes.
                Fluent English required.
                """;

        MatchReport r = scorer.score(backendCv(), noPrefs(), job("Director of Product", ad), true);

        MatchReport.ComponentResult required = comp(r, "requiredSkills");
        assertThat(required.score()).isEqualTo(1.0);
        assertThat(required.confidence()).isLessThan(0.25); // one skill, and only mentioned in passing
        assertThat(r.score()).isLessThan(45);
        // Only roles related to the offer count towards its years requirement: the Kubernetes role, not ten years of Java.
        assertThat(comp(r, "experience").score()).isLessThan(0.5);
        assertThat(r.gaps()).extracting(MatchReport.Note::code).contains("gap.experience");
    }

    @Test
    void relatedRolesCountTowardsRequiredYearsByTitleOrSkill() {
        String ad = """
                Requirements
                - 5+ years of experience
                - Java
                """;

        MatchReport r = scorer.score(backendCv(), noPrefs(), job("Java Developer", ad), true);

        // Both roles mention Java or share the title, so all dated years count.
        assertThat(comp(r, "experience").score()).isEqualTo(1.0);
        assertThat(r.strengths()).extracting(MatchReport.Note::code).contains("strength.experience");
    }

    @Test
    void moreNamedSkillsMeanMoreConfidenceInTheSkillCriterion() {
        MatchReport one = scorer.score(backendCv(), noPrefs(), job("Engineer", "Requirements\n- Java\n"), true);
        MatchReport three = scorer.score(backendCv(), noPrefs(), job("Engineer", "Requirements\n- Java\n- Spring Boot\n- PostgreSQL\n"), true);

        assertThat(comp(one, "requiredSkills").confidence()).isEqualTo(1.0 / 3.0);
        assertThat(comp(three, "requiredSkills").confidence()).isEqualTo(1.0);
    }

    @Test
    void goldenScoresStayStable() {
        // Pinned so that any change to the method is deliberate. Update with the scoring documentation.
        assertThat(scorer.score(backendCv(), noPrefs(), job("Senior Java Developer", SENIOR_JAVA_AD), true).score()).isEqualTo(GOLDEN_STRONG);
        assertThat(scorer.score(backendCv(), noPrefs(), job("Data Scientist", DATA_SCIENCE_AD), true).score()).isEqualTo(GOLDEN_WEAK);
        assertThat(scorer.score(backendCv(), noPrefs(), job("Software Engineer", PARTIAL_AD), true).score()).isEqualTo(GOLDEN_PARTIAL);
    }

    private static final String PARTIAL_AD = """
            Requirements
            - Java and Spring Boot
            - Python scripting
            - Docker

            Nice to have
            - Terraform
            """;
    private static final int GOLDEN_PARTIAL = 67;

    private static final int GOLDEN_STRONG = 96;
    private static final int GOLDEN_WEAK = 0;
}
