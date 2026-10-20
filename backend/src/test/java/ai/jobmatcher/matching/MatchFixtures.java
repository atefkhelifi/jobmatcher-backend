package ai.jobmatcher.matching;

import java.util.List;

import ai.jobmatcher.cv.CvParser;
import ai.jobmatcher.cv.ParsedCv;
import ai.jobmatcher.cv.SkillCatalog;
import ai.jobmatcher.jobs.RemoteMode;
import ai.jobmatcher.profile.ProfileService.Preferences;

/** Synthetic candidates and job ads. All names and companies are invented. */
public final class MatchFixtures {

    private MatchFixtures() {
    }

    public static final SkillCatalog CATALOG = new SkillCatalog();
    public static final CvParser PARSER = new CvParser(CATALOG);

    public static final String BACKEND_CV = """
            Jane Doe
            jane.doe@example.org | +33 6 12 34 56 78

            Profile
            Backend engineer focused on Java services and PostgreSQL databases for logistics teams.

            Experience
            Senior Java Developer - Northwind Labs
            Jan 2019 - Present
            - Designed REST APIs with Spring Boot used by 12 internal teams
            - Reduced report generation time by 40% by tuning PostgreSQL queries
            - Introduced CI/CD with GitHub Actions and Docker

            Java Developer | Contoso Systems
            03/2016 - 12/2018
            - Migrated a monolith to microservices on Kubernetes

            Education
            Master in Computer Science - University of Lyon 2012 - 2014

            Skills
            Java, Spring Boot, PostgreSQL, Docker, Kubernetes, Git

            Languages
            English (C1), French (native)
            """;

    public static ParsedCv backendCv() {
        return PARSER.parse(BACKEND_CV);
    }

    public static final String SENIOR_JAVA_AD = """
            About the role
            We build logistics software and need a Senior Java Developer.

            Requirements
            - 5+ years of experience with Java
            - Strong knowledge of Spring Boot and PostgreSQL
            - Fluent English

            Nice to have
            - Kafka
            - Kubernetes
            """;

    public static final String DATA_SCIENCE_AD = """
            Requirements
            - Python and Pandas for data analysis
            - TensorFlow or PyTorch for deep learning
            - Spark for large-scale processing
            """;

    public static MatchScorer.JobFacts job(String title, String description) {
        return new MatchScorer.JobFacts(title, description, null, null, RemoteMode.UNKNOWN, null, null, null, null, false);
    }

    public static MatchScorer.JobFacts job(String title, String description, String location, String country, RemoteMode remote,
            String type, Integer salaryMax, String currency) {
        return new MatchScorer.JobFacts(title, description, location, country, remote, type, null, salaryMax, currency, false);
    }

    public static Preferences noPrefs() {
        return prefs(List.of(), null, List.of(), List.of(), List.of(), List.of(), false, null, null, null);
    }

    public static Preferences prefs(List<String> titles, String seniority, List<String> contracts, List<String> modes, List<String> countries,
            List<String> cities, boolean relocate, Boolean visa, Integer salaryMin, String currency) {
        return new Preferences(titles, List.of(), seniority, salaryMin, null, currency, contracts, modes, countries, cities, relocate, visa,
                List.of(), true, true);
    }

    public static MatchScorer scorer() {
        return new MatchScorer(CATALOG, new JobSkillExtractor(CATALOG), MatchWeights.defaults());
    }

    public static MatchScorer scorer(MatchWeights w) {
        return new MatchScorer(CATALOG, new JobSkillExtractor(CATALOG), w);
    }
}
