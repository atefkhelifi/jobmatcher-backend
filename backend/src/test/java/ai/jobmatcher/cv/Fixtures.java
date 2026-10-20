package ai.jobmatcher.cv;

/** Synthetic CVs. All people, employers and contact details are invented. */
final class Fixtures {

    private Fixtures() {
    }

    static final String EN = """
            Jane Doe
            jane.doe@example.org | +33 6 12 34 56 78

            Profile
            Backend engineer with a focus on Java and PostgreSQL, building reliable APIs for logistics and retail teams.

            Experience
            Senior Java Developer - Northwind Labs
            Jan 2021 - Present
            • Designed REST APIs with Spring Boot used by 12 internal teams.
            • Reduced report generation time by 40% by tuning PostgreSQL queries.
            • Led a team of 4 developers and introduced CI/CD with GitHub Actions and Docker.

            Java Developer | Contoso Systems
            03/2018 - 12/2020
            - Responsible for maintaining legacy modules
            - Migrated a monolith to microservices on Kubernetes

            Education
            Master in Computer Science - University of Lyon 2014 - 2016

            Skills
            Java, Spring Boot, PostgreSQL, Docker, Kubernetes, Git, Angular, Go

            Languages
            English (C1), French (native)

            Certifications
            • AWS Certified Developer
            """;

    static final String FR = """
            Camille Martin
            camille.martin@example.org
            06 11 22 33 44

            Profil professionnel
            Développeuse full stack avec 6 ans d'expérience en Angular et Java, passionnée par la qualité logicielle.

            Expériences professionnelles
            Développeuse full stack chez Fabrikam
            Septembre 2019 - Aujourd'hui
            - Mise en place d'une architecture microservices avec Spring Boot et Kafka
            - Responsable de la formation de 3 juniors sur Angular et TypeScript

            Formation
            Diplôme d'ingénieur - École Polytechnique de Test, 2015 - 2018

            Compétences
            Angular, TypeScript, Java, Spring Boot, Kafka, Docker, Scrum

            Langues
            Français (langue maternelle), Anglais (courant)
            """;
}
