package ai.jobmatcher.applications;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where an application stands. Moves are limited to the ones that make sense: an application cannot jump from "discovered" to
 * "offer", and REJECTED and WITHDRAWN are final. Withdrawing is possible from any open stage.
 */
public enum ApplicationStatus {
    DISCOVERED, TO_REVIEW, SHORTLISTED, PREPARING, APPLIED, INTERVIEW, ASSESSMENT, OFFER, REJECTED, WITHDRAWN;

    public Set<ApplicationStatus> next() {
        return switch (this) {
            case DISCOVERED -> EnumSet.of(TO_REVIEW, SHORTLISTED, PREPARING, APPLIED, WITHDRAWN);
            case TO_REVIEW -> EnumSet.of(DISCOVERED, SHORTLISTED, PREPARING, APPLIED, WITHDRAWN);
            case SHORTLISTED -> EnumSet.of(TO_REVIEW, PREPARING, APPLIED, WITHDRAWN);
            case PREPARING -> EnumSet.of(SHORTLISTED, APPLIED, WITHDRAWN);
            case APPLIED -> EnumSet.of(INTERVIEW, ASSESSMENT, OFFER, REJECTED, WITHDRAWN);
            case ASSESSMENT -> EnumSet.of(INTERVIEW, OFFER, REJECTED, WITHDRAWN);
            case INTERVIEW -> EnumSet.of(ASSESSMENT, OFFER, REJECTED, WITHDRAWN);
            case OFFER -> EnumSet.of(REJECTED, WITHDRAWN);
            case REJECTED, WITHDRAWN -> EnumSet.noneOf(ApplicationStatus.class);
        };
    }

    public boolean canMoveTo(ApplicationStatus target) {
        return next().contains(target);
    }

    public boolean isFinal() {
        return next().isEmpty();
    }

    /** Statuses a new application may start in: before or at the moment of applying. */
    public boolean canStartHere() {
        return ordinal() <= APPLIED.ordinal();
    }
}
