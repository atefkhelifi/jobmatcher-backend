package ai.jobmatcher.documents;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Builds the application email: subject, greeting, target role, a short introduction, two or three qualifications taken
 * from the CV facts, a pointer to the attachments, and a closing. The email is a draft for the user; nothing is sent.
 */
@Component
public class EmailComposer {

    public record Email(String subject, String body, List<String> factIds) {
    }

    public Email compose(EvidenceSelector.Context ctx, LetterOptions o, LetterLocale l, boolean withLetter, String contactName) {
        StringBuilder sb = new StringBuilder();
        sb.append(l.emailGreeting(contactName, o.formality())).append("\n\n");
        sb.append(l.emailIntro(ctx.jobTitle(), ctx.company(), ctx.currentTitle(), ctx.relatedYears())).append("\n\n");

        Set<String> used = new LinkedHashSet<>();
        List<String> points = new ArrayList<>();
        for (Fact f : ctx.ofType(Fact.Type.EXPERIENCE)) {
            if (points.size() >= 2) {
                break;
            }
            points.add("- " + (f.title() == null ? "" : f.title() + (f.company() == null ? "" : ", " + f.company()) + ": ") + l.stripEnd(f.text()));
            used.add(f.id());
        }
        List<Fact> skillFacts = ctx.ofType(Fact.Type.SKILL).stream().limit(5).toList();
        if (!skillFacts.isEmpty()) {
            points.add("- " + l.emailSkillsLine(skillFacts.stream().flatMap(f -> f.skills().stream()).distinct().toList()));
            skillFacts.forEach(f -> used.add(f.id()));
        }
        if (!points.isEmpty()) {
            sb.append(l.emailPointsIntro()).append('\n');
            points.forEach(p -> sb.append(p).append('\n'));
            sb.append('\n');
        }

        sb.append(l.emailAttachments(withLetter)).append("\n\n");
        sb.append(l.emailClosing(o.formality())).append('\n');
        if (ctx.candidateName() != null) {
            sb.append(ctx.candidateName()).append('\n');
        }
        if (ctx.email() != null) {
            sb.append(ctx.email()).append('\n');
        }
        if (ctx.phone() != null) {
            sb.append(ctx.phone()).append('\n');
        }
        return new Email(l.emailSubject(ctx.jobTitle(), ctx.company()), sb.toString().strip(), List.copyOf(used));
    }
}
