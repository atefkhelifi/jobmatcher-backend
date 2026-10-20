package ai.jobmatcher.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import ai.jobmatcher.jobs.JobQualityAnalyzer.Severity;
import ai.jobmatcher.jobs.JobQualityAnalyzer.Warning;

import org.junit.jupiter.api.Test;

class JobQualityAnalyzerTest {

    private final JobQualityAnalyzer analyzer = new JobQualityAnalyzer();

    private static final String LONG = "Backend Engineer. ".repeat(30);

    private List<String> codes(String title, String company, String description) {
        return analyzer.analyze(title, company, description).stream().map(Warning::code).toList();
    }

    @Test
    void anOrdinaryAdHasNoWarnings() {
        String ad = "Requirements\n- Java and Spring Boot\n- Three years of experience with REST APIs\nWe offer a hybrid schedule, 30 days of leave and a "
                + "learning budget. Apply with your CV through the careers page. " + LONG;
        assertThat(codes("Java Developer", "Acme GmbH", ad)).isEmpty();
    }

    @Test
    void anUpfrontFeeIsFlaggedWithTheQuotedWords() {
        List<Warning> w = analyzer.analyze("Data entry", "Acme", LONG + " A refundable registration fee of 50 EUR is required before the first task.");
        assertThat(w).extracting(Warning::code).contains("FEE_REQUESTED");
        Warning fee = w.stream().filter(x -> x.code().equals("FEE_REQUESTED")).findFirst().orElseThrow();
        assertThat(fee.severity()).isEqualTo(Severity.CAUTION);
        assertThat(fee.evidence()).containsIgnoringCase("registration fee");
    }

    @Test
    void frenchFeesAndBankDocumentsAreRecognised() {
        assertThat(codes("Assistant", "Société X", LONG + " Des frais d'inscription de 40 euros sont demandés.")).contains("FEE_REQUESTED");
        assertThat(codes("Assistant", "Société X", LONG + " Envoyez votre carte d'identité et votre RIB par message.")).contains("SENSITIVE_DOCUMENTS");
    }

    @Test
    void sensitiveDocumentsAreOnlyFlaggedWhenTheyAreRequested() {
        assertThat(codes("Engineer", "Acme", LONG + " We process bank statements for our fintech clients.")).doesNotContain("SENSITIVE_DOCUMENTS");
        assertThat(codes("Engineer", "Acme", LONG + " Please send a copy of your passport and bank details to start.")).contains("SENSITIVE_DOCUMENTS");
    }

    @Test
    void messagingAppsAndUnrealisticEarningsAreFlagged() {
        assertThat(codes("Assistant", "Acme", LONG + " Contact our manager on WhatsApp to begin.")).contains("MESSAGING_APP_CONTACT");
        assertThat(codes("Assistant", "Acme", LONG + " Easy money from home, earn up to $500 per day.")).contains("UNREALISTIC_EARNINGS");
        assertThat(codes("Assistant", "Acme", LONG + " Gagnez 300 € par jour sans diplôme.")).contains("UNREALISTIC_EARNINGS");
    }

    @Test
    void urgencyAndPersonalMailboxesAreOnlyNotes() {
        List<Warning> w = analyzer.analyze("Assistant", "Acme", LONG + " Urgent hiring! Send your CV to recruiter.jobs@gmail.com");
        assertThat(w).extracting(Warning::code).contains("URGENCY_PRESSURE", "PERSONAL_EMAIL_CONTACT");
        assertThat(w).allMatch(x -> x.severity() == Severity.NOTE);
    }

    @Test
    void missingCompanyAndVeryShortAdsAreNotedWithoutEvidence() {
        List<Warning> w = analyzer.analyze("Assistant", " ", "Short ad.");
        assertThat(w).extracting(Warning::code).containsExactlyInAnyOrder("NO_COMPANY", "VERY_SHORT");
        assertThat(w).allMatch(x -> x.evidence() == null);
    }

    @Test
    void cautionsComeBeforeNotes() {
        List<Warning> w = analyzer.analyze("Assistant", null, LONG + " Pay a small fee to start. Urgent hiring.");
        assertThat(w.get(0).severity()).isEqualTo(Severity.CAUTION);
        assertThat(w.get(w.size() - 1).severity()).isEqualTo(Severity.NOTE);
    }
}
