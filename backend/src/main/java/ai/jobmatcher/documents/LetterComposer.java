package ai.jobmatcher.documents;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Deterministic letter builder. It assembles sentences from the selected CV facts and the locale's wording, so it works with
 * no language model and can never add a claim the CV does not make. The optional AI writer may improve the prose, but only
 * output that passes the grounding check replaces this draft.
 */
@Component
public class LetterComposer {

    public LetterDraft compose(EvidenceSelector.Context ctx, LetterOptions o, LetterLocale l) {
        List<LetterDraft.Paragraph> body = new ArrayList<>();
        body.add(new LetterDraft.Paragraph(l.opening(ctx.jobTitle(), ctx.company(), o.tone()), List.of()));

        int maxExperience = switch (o.length()) {
            case SHORT -> 1;
            case STANDARD -> 2;
            case DETAILED -> 3;
        };
        List<Fact> experience = ctx.ofType(Fact.Type.EXPERIENCE);
        for (Fact e : experience.stream().limit(maxExperience).toList()) {
            body.add(new LetterDraft.Paragraph(l.experienceLine(e), List.of(e.id())));
        }

        if (o.length() != LetterOptions.Length.SHORT) {
            List<Fact> skills = ctx.ofType(Fact.Type.SKILL);
            List<Fact> years = ctx.ofType(Fact.Type.YEARS);
            if (!years.isEmpty()) {
                List<String> names = skills.stream().flatMap(f -> f.skills().stream()).limit(4).toList();
                List<String> ids = new ArrayList<>(List.of(years.get(0).id()));
                skills.stream().limit(4).forEach(f -> ids.add(f.id()));
                body.add(new LetterDraft.Paragraph(l.yearsLine(ctx.relatedYears(), names), ids));
            } else if (!skills.isEmpty()) {
                List<Fact> used = skills.stream().limit(4).toList();
                body.add(new LetterDraft.Paragraph(l.skillsLine(used.stream().flatMap(f -> f.skills().stream()).toList()),
                        used.stream().map(Fact::id).toList()));
            }
        }

        if (o.length() == LetterOptions.Length.DETAILED) {
            ctx.ofType(Fact.Type.EDUCATION).stream().findFirst()
                    .ifPresent(f -> body.add(new LetterDraft.Paragraph(l.educationLine(f.text()), List.of(f.id()))));
            ctx.ofType(Fact.Type.LANGUAGE).stream().findFirst()
                    .ifPresent(f -> body.add(new LetterDraft.Paragraph(l.languageLine(f.text()), List.of(f.id()))));
            if (ctx.desiredTitle() != null) {
                body.add(new LetterDraft.Paragraph(l.motivationLine(ctx.desiredTitle()), List.of()));
            }
        }

        body.add(new LetterDraft.Paragraph(l.closingParagraph(o.tone()), List.of()));
        return new LetterDraft(l.salutation(o.formality()), List.copyOf(body), l.closingFormula(o.formality()),
                ctx.candidateName() == null ? "" : ctx.candidateName(), "template");
    }
}
