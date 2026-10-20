package ai.jobmatcher.documents;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class EnglishLocale implements LetterLocale {

    @Override
    public String code() {
        return "en";
    }

    @Override
    public String salutation(LetterOptions.Formality f) {
        return f == LetterOptions.Formality.FORMAL ? "Dear Sir or Madam," : "Hello,";
    }

    @Override
    public String opening(String title, String company, LetterOptions.Tone tone) {
        String role = company == null ? "the " + title + " position you have advertised" : "the " + title + " position at " + company;
        return switch (tone) {
            case WARM -> "I was glad to see " + role + ", and I would like to apply for it.";
            case DIRECT -> "I am applying for " + role + ".";
            case NEUTRAL -> "I am writing to apply for " + role + ".";
        };
    }

    @Override
    public String skillsLine(List<String> skills) {
        return "My background includes " + join(skills, "and") + ", which your posting asks for.";
    }

    @Override
    public String experienceLine(Fact e) {
        StringBuilder sb = new StringBuilder();
        if (e.title() != null) {
            sb.append("I worked as ").append(e.title());
            if (e.company() != null) {
                sb.append(" at ").append(e.company());
            }
            if (e.period() != null) {
                sb.append(" (").append(period(e.period(), "present")).append(")");
            }
            sb.append(". ");
        }
        sb.append("In that role: ").append(stripEnd(e.text())).append('.');
        return sb.toString();
    }

    @Override
    public String yearsLine(String years, List<String> skills) {
        String base = "I have about " + years + " years of experience in roles related to this one";
        return skills.isEmpty() ? base + "." : base + ", working with " + join(skills, "and") + ".";
    }

    @Override
    public String educationLine(String text) {
        return "My education: " + stripEnd(text) + ".";
    }

    @Override
    public String languageLine(String text) {
        return "Your posting asks for language skills; my CV states: " + stripEnd(text) + ".";
    }

    @Override
    public String motivationLine(String desiredTitle) {
        return "This role matches the direction I am looking for: " + desiredTitle + ".";
    }

    @Override
    public String closingParagraph(LetterOptions.Tone tone) {
        return switch (tone) {
            case WARM -> "I would be glad to talk about how my experience could serve your team. Thank you for your time and consideration.";
            case DIRECT -> "I would welcome an interview. Thank you for considering my application.";
            case NEUTRAL -> "I would welcome the opportunity to discuss how my experience fits your needs. Thank you for considering my application.";
        };
    }

    @Override
    public String closingFormula(LetterOptions.Formality f) {
        return f == LetterOptions.Formality.FORMAL ? "Yours faithfully," : "Kind regards,";
    }

    @Override
    public String emailSubject(String title, String company) {
        return "Application: " + title + (company == null ? "" : " (" + company + ")");
    }

    @Override
    public String emailGreeting(String contactName, LetterOptions.Formality f) {
        if (contactName != null) {
            return "Dear " + contactName + ",";
        }
        return f == LetterOptions.Formality.FORMAL ? "Dear Hiring Team," : "Hello,";
    }

    @Override
    public String emailIntro(String title, String company, String currentTitle, String years) {
        String s = "I am applying for the " + title + " position" + (company == null ? "" : " at " + company) + ".";
        if (currentTitle != null) {
            s += " I work as " + currentTitle + (years == null ? "" : " and have about " + years + " years of related experience") + ".";
        } else if (years != null) {
            s += " I have about " + years + " years of related experience.";
        }
        return s;
    }

    @Override
    public String emailPointsIntro() {
        return "What matches your posting:";
    }

    @Override
    public String emailSkillsLine(List<String> skills) {
        return "Skills from your posting that appear on my CV: " + join(skills, "and");
    }

    @Override
    public String emailAttachments(boolean withLetter) {
        return withLetter ? "My CV and cover letter are attached." : "My CV is attached.";
    }

    @Override
    public String emailClosing(LetterOptions.Formality f) {
        return f == LetterOptions.Formality.FORMAL ? "Yours sincerely," : "Kind regards,";
    }
}
