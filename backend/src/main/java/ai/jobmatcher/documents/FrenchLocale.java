package ai.jobmatcher.documents;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class FrenchLocale implements LetterLocale {

    @Override
    public String code() {
        return "fr";
    }

    @Override
    public String salutation(LetterOptions.Formality f) {
        return f == LetterOptions.Formality.FORMAL ? "Madame, Monsieur," : "Bonjour,";
    }

    @Override
    public String opening(String title, String company, LetterOptions.Tone tone) {
        String role = company == null ? "le poste de " + title + " que vous proposez" : "le poste de " + title + " au sein de " + company;
        return switch (tone) {
            case WARM -> "Votre annonce pour " + role + " a retenu toute mon attention, et je souhaite vous présenter ma candidature.";
            case DIRECT -> "Je vous adresse ma candidature pour " + role + ".";
            case NEUTRAL -> "Je vous écris afin de poser ma candidature pour " + role + ".";
        };
    }

    @Override
    public String skillsLine(List<String> skills) {
        return "Mon parcours comprend " + join(skills, "et") + ", que votre annonce demande.";
    }

    @Override
    public String experienceLine(Fact e) {
        StringBuilder sb = new StringBuilder();
        if (e.title() != null) {
            sb.append("J'ai occupé le poste de ").append(e.title());
            if (e.company() != null) {
                sb.append(" chez ").append(e.company());
            }
            if (e.period() != null) {
                sb.append(" (").append(period(e.period(), "aujourd'hui")).append(")");
            }
            sb.append(". ");
        }
        sb.append("Dans ce poste : ").append(stripEnd(e.text())).append('.');
        return sb.toString();
    }

    @Override
    public String yearsLine(String years, List<String> skills) {
        String base = "Je compte environ " + years + " ans d'expérience dans des postes proches de celui-ci";
        return skills.isEmpty() ? base + "." : base + ", avec " + join(skills, "et") + ".";
    }

    @Override
    public String educationLine(String text) {
        return "Ma formation : " + stripEnd(text) + ".";
    }

    @Override
    public String languageLine(String text) {
        return "Votre annonce mentionne des compétences linguistiques ; mon CV indique : " + stripEnd(text) + ".";
    }

    @Override
    public String motivationLine(String desiredTitle) {
        return "Ce poste correspond à l'orientation que je recherche : " + desiredTitle + ".";
    }

    @Override
    public String closingParagraph(LetterOptions.Tone tone) {
        return switch (tone) {
            case WARM -> "Je serais heureux(se) d'échanger sur la façon dont mon expérience pourrait servir votre équipe. Je vous remercie de votre attention.";
            case DIRECT -> "Je reste disponible pour un entretien. Je vous remercie de l'attention portée à ma candidature.";
            case NEUTRAL -> "Je serais ravi(e) de vous rencontrer pour discuter de l'adéquation de mon expérience avec vos besoins. Je vous remercie de l'attention portée à ma candidature.";
        };
    }

    @Override
    public String closingFormula(LetterOptions.Formality f) {
        return f == LetterOptions.Formality.FORMAL
                ? "Je vous prie d'agréer, Madame, Monsieur, l'expression de mes salutations distinguées."
                : "Cordialement,";
    }

    @Override
    public String emailSubject(String title, String company) {
        return "Candidature : " + title + (company == null ? "" : " (" + company + ")");
    }

    @Override
    public String emailGreeting(String contactName, LetterOptions.Formality f) {
        if (contactName != null) {
            return "Bonjour " + contactName + ",";
        }
        return f == LetterOptions.Formality.FORMAL ? "Madame, Monsieur," : "Bonjour,";
    }

    @Override
    public String emailIntro(String title, String company, String currentTitle, String years) {
        String s = "Je vous écris pour candidater au poste de " + title + (company == null ? "" : " chez " + company) + ".";
        if (currentTitle != null) {
            s += " Je travaille comme " + currentTitle + (years == null ? "" : " et compte environ " + years + " ans d'expérience dans des postes proches") + ".";
        } else if (years != null) {
            s += " Je compte environ " + years + " ans d'expérience dans des postes proches.";
        }
        return s;
    }

    @Override
    public String emailPointsIntro() {
        return "Ce qui correspond à votre annonce :";
    }

    @Override
    public String emailSkillsLine(List<String> skills) {
        return "Compétences de votre annonce présentes sur mon CV : " + join(skills, "et");
    }

    @Override
    public String emailAttachments(boolean withLetter) {
        return withLetter ? "Vous trouverez ci-joint mon CV et ma lettre de motivation." : "Vous trouverez ci-joint mon CV.";
    }

    @Override
    public String emailClosing(LetterOptions.Formality f) {
        return f == LetterOptions.Formality.FORMAL ? "Je vous prie d'agréer mes salutations distinguées." : "Cordialement,";
    }
}
