package ai.jobmatcher.applications;

import static ai.jobmatcher.applications.ApplicationStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ApplicationStatusTest {

    @Test
    void allowedMovesFollowTheRealProcess() {
        assertThat(DISCOVERED.next()).containsExactlyInAnyOrder(TO_REVIEW, SHORTLISTED, PREPARING, APPLIED, WITHDRAWN);
        assertThat(PREPARING.next()).containsExactlyInAnyOrder(SHORTLISTED, APPLIED, WITHDRAWN);
        assertThat(APPLIED.next()).containsExactlyInAnyOrder(INTERVIEW, ASSESSMENT, OFFER, REJECTED, WITHDRAWN);
        assertThat(INTERVIEW.next()).containsExactlyInAnyOrder(ASSESSMENT, OFFER, REJECTED, WITHDRAWN);
        assertThat(ASSESSMENT.next()).containsExactlyInAnyOrder(INTERVIEW, OFFER, REJECTED, WITHDRAWN);
        assertThat(OFFER.next()).containsExactlyInAnyOrder(REJECTED, WITHDRAWN);
    }

    @Test
    void impossibleJumpsAreRefused() {
        assertThat(DISCOVERED.canMoveTo(OFFER)).isFalse();
        assertThat(TO_REVIEW.canMoveTo(INTERVIEW)).isFalse();
        assertThat(PREPARING.canMoveTo(REJECTED)).isFalse(); // nobody rejected an application that was never sent
        assertThat(APPLIED.canMoveTo(PREPARING)).isFalse();
        assertThat(OFFER.canMoveTo(APPLIED)).isFalse();
        assertThat(OFFER.canMoveTo(INTERVIEW)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = { "REJECTED", "WITHDRAWN" })
    void finalStatusesAreFinal(ApplicationStatus s) {
        assertThat(s.isFinal()).isTrue();
        assertThat(s.next()).isEmpty();
        for (ApplicationStatus other : values()) {
            assertThat(s.canMoveTo(other)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void noStatusMovesToItself(ApplicationStatus s) {
        assertThat(s.canMoveTo(s)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void everyOpenStatusCanBeWithdrawnAndEveryStatusCanEnd(ApplicationStatus s) {
        if (!s.isFinal()) {
            assertThat(s.canMoveTo(WITHDRAWN)).as(s + " can be withdrawn").isTrue();
            assertThat(reachable(s)).as(s + " reaches an end state").anyMatch(ApplicationStatus::isFinal);
        }
    }

    @Test
    void aSuccessfulPathExistsFromTheStartToAnOffer() {
        assertThat(reachable(DISCOVERED)).contains(APPLIED, INTERVIEW, OFFER);
    }

    @Test
    void newApplicationsMayStartBeforeOrAtApplying() {
        assertThat(EnumSet.allOf(ApplicationStatus.class).stream().filter(ApplicationStatus::canStartHere))
                .containsExactlyInAnyOrder(DISCOVERED, TO_REVIEW, SHORTLISTED, PREPARING, APPLIED);
    }

    private static Set<ApplicationStatus> reachable(ApplicationStatus from) {
        Set<ApplicationStatus> seen = EnumSet.noneOf(ApplicationStatus.class);
        Deque<ApplicationStatus> queue = new ArrayDeque<>(from.next());
        while (!queue.isEmpty()) {
            ApplicationStatus s = queue.poll();
            if (seen.add(s)) {
                queue.addAll(s.next());
            }
        }
        return seen;
    }
}
