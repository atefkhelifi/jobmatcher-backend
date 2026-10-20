package ai.jobmatcher.interview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import ai.jobmatcher.common.InvalidInputException;
import ai.jobmatcher.common.NotFoundException;
import ai.jobmatcher.jobs.JobOffer;
import ai.jobmatcher.jobs.JobOfferRepository;
import ai.jobmatcher.learning.LearningCatalog;
import ai.jobmatcher.matching.MatchReport;
import ai.jobmatcher.matching.MatchService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interview preparation for one job. Questions come from the skills the ad asks for. Where the reviewed CV shows a skill, the
 * question carries the CV's own words as evidence and a STAR frame for the user to fill in; where it does not, the question is
 * about the gap and says so plainly. Nothing is written for the user to claim: the scaffolds are prompts, never answers.
 */
@Service
public class InterviewPrepService {

    public enum Kind { EXPERIENCE, GAP, BEHAVIORAL, MOTIVATION, ASK_THEM }

    /** {@code evidence} is a quote from the user's CV, or null. {@code scaffold} are prompts the user answers themselves. */
    public record Question(String id, Kind kind, String skill, String text, String evidence, boolean weakEvidence, List<String> scaffold) {
    }

    public record Revision(String skill, String level, boolean shownOnCv, List<LearningCatalog.Resource> resources) {
    }

    public record Prep(UUID jobId, String title, String company, String language, boolean cvReviewed, List<Question> questions,
            List<Revision> revision) {
    }

    static final int MAX_SKILL_QUESTIONS = 6;
    static final int MAX_REVISION = 8;

    private final JobOfferRepository offers;
    private final MatchService matching;
    private final LearningCatalog catalog;

    InterviewPrepService(JobOfferRepository offers, MatchService matching, LearningCatalog catalog) {
        this.offers = offers;
        this.matching = matching;
        this.catalog = catalog;
    }

    @Transactional
    public Prep prepare(UUID userId, UUID jobId, UUID cvId, String language) {
        String lang = language != null && language.toLowerCase(Locale.ROOT).startsWith("fr") ? "fr" : "en";
        JobOffer job = offers.findById(jobId).filter(o -> o.getOwnerUserId() == null || o.getOwnerUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Job not found."));
        if (cvId == null) {
            throw new InvalidInputException("Choose a CV to prepare with.");
        }
        MatchReport report = matching.match(userId, cvId, jobId).report();
        return build(job.getId(), job.getTitle(), job.getCompany(), lang, report, catalog);
    }

    static Prep build(UUID jobId, String title, String company, String lang, MatchReport report, LearningCatalog catalog) {
        boolean fr = lang.equals("fr");
        List<Question> qs = new ArrayList<>();
        int n = 0;
        List<MatchReport.SkillResult> ordered = report.skills().stream()
                .sorted((a, b) -> Integer.compare(rank(a), rank(b))).limit(MAX_SKILL_QUESTIONS).toList();
        for (MatchReport.SkillResult s : ordered) {
            n++;
            if (s.matched()) {
                qs.add(new Question("q" + n, Kind.EXPERIENCE, s.skill(),
                        fr ? "Parlez-moi d'un projet où vous avez utilisé " + s.skill() + ". Qu'avez-vous fait concrètement ?"
                                : "Tell me about a project where you used " + s.skill() + ". What exactly did you do?",
                        s.evidence(), s.inferred(), star(fr)));
            } else {
                qs.add(new Question("q" + n, Kind.GAP, s.skill(),
                        fr ? "L'annonce demande " + s.skill() + ". Votre CV ne le montre pas : comment aborderiez-vous ce point ?"
                                : "The ad asks for " + s.skill() + ". Your CV does not show it: how would you address that?",
                        null, false, gap(fr)));
            }
        }
        String role = title == null ? "" : title;
        String at = company == null || company.isBlank() ? "" : (fr ? " chez " : " at ") + company;
        qs.add(new Question("b1", Kind.BEHAVIORAL, null,
                fr ? "Racontez une situation où vous avez dû résoudre un désaccord technique dans une équipe."
                        : "Describe a time you had to resolve a technical disagreement in a team.", null, false, star(fr)));
        qs.add(new Question("b2", Kind.BEHAVIORAL, null,
                fr ? "Parlez d'une erreur que vous avez commise au travail et de ce que vous en avez appris."
                        : "Tell me about a mistake you made at work and what you learned from it.", null, false, star(fr)));
        qs.add(new Question("b3", Kind.BEHAVIORAL, null,
                fr ? "Comment organisez-vous votre travail quand plusieurs échéances se chevauchent ?"
                        : "How do you organise your work when several deadlines overlap?", null, false, star(fr)));
        qs.add(new Question("m1", Kind.MOTIVATION, null,
                fr ? "Pourquoi ce poste de " + role + at + " vous intéresse-t-il ?" : "Why does this " + role + " role" + at + " interest you?",
                null, false, fr ? List.of("Ce qui vous attire dans les missions décrites dans l'annonce", "Ce que vous avez vérifié sur l'entreprise (site officiel)",
                        "Le lien avec ce que vous voulez faire ensuite") : List.of("What attracts you in the tasks described in the ad",
                        "What you checked about the company (its official website)", "How it connects to what you want to do next")));
        qs.add(new Question("a1", Kind.ASK_THEM, null,
                fr ? "Quelles seront les priorités de la personne recrutée pendant les trois premiers mois ?"
                        : "What will the priorities of the person you hire be in the first three months?", null, false, List.of()));
        qs.add(new Question("a2", Kind.ASK_THEM, null,
                fr ? "Comment l'équipe travaille-t-elle au quotidien (revues de code, déploiements, astreintes) ?"
                        : "How does the team work day to day (code review, releases, on-call)?", null, false, List.of()));
        qs.add(new Question("a3", Kind.ASK_THEM, null,
                fr ? "Quelles sont les prochaines étapes du processus de recrutement et leur calendrier ?"
                        : "What are the next steps in the hiring process, and when?", null, false, List.of()));

        List<Revision> revision = report.skills().stream().sorted((a, b) -> Integer.compare(rank(a), rank(b))).limit(MAX_REVISION)
                .map(s -> new Revision(s.skill(), s.level(), s.matched(), catalog.forSkill(s.skill()))).toList();
        return new Prep(jobId, title, company, lang, report.cvReviewed(), qs, revision);
    }

    /** Required skills first, then preferred; within a level, the skills the CV does not show come first because they need the most work. */
    private static int rank(MatchReport.SkillResult s) {
        return ("REQUIRED".equalsIgnoreCase(s.level()) ? 0 : 2) + (s.matched() ? 1 : 0);
    }

    private static List<String> star(boolean fr) {
        return fr ? List.of("Situation : le contexte et l'enjeu", "Tâche : ce qui vous incombait précisément", "Action : ce que vous avez fait, vous, étape par étape",
                "Résultat : l'effet mesurable, avec un chiffre si vous en avez un")
                : List.of("Situation: the context and what was at stake", "Task: what you were responsible for", "Action: what you did, step by step",
                        "Result: the measurable outcome, with a number if you have one");
    }

    private static List<String> gap(boolean fr) {
        return fr ? List.of("Dites-le franchement : vous ne l'avez pas pratiqué professionnellement", "Citez l'expérience la plus proche que vous avez réellement",
                "Expliquez comment et en combien de temps vous le maîtriseriez")
                : List.of("Say plainly that you have not used it professionally", "Name the closest experience you actually have",
                        "Explain how, and how quickly, you would get up to speed");
    }
}
