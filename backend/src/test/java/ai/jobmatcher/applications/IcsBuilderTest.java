package ai.jobmatcher.applications;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class IcsBuilderTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");

    @Test
    void producesAValidCalendarWithCrlfLineEndings() {
        String ics = IcsBuilder.build("Tracker", List.of(
                IcsBuilder.Event.timed("a@jobmatcher", "Interview (video): Backend Engineer - Acme", "Bring the portfolio", "https://meet.example/abc",
                        Instant.parse("2026-11-03T13:30:00Z"), 45, true)), NOW);

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n").endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n");
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n");
        assertThat(ics).contains("UID:a@jobmatcher").contains("DTSTAMP:20261020T080000Z").contains("DTSTART:20261103T133000Z")
                .contains("DTEND:20261103T141500Z").contains("SUMMARY:Interview (video): Backend Engineer - Acme")
                .contains("LOCATION:https://meet.example/abc").contains("BEGIN:VALARM").contains("TRIGGER:PT0M");
    }

    @Test
    void escapesSpecialCharactersInText() {
        String ics = IcsBuilder.build("T", List.of(IcsBuilder.Event.timed("b@x", "Call; bring: notes, CV\\copy", "Line one\nLine two", null,
                Instant.parse("2026-11-03T13:30:00Z"), 30, false)), NOW);

        assertThat(ics).contains("SUMMARY:Call\\; bring: notes\\, CV\\\\copy").contains("DESCRIPTION:Line one\\nLine two").doesNotContain("BEGIN:VALARM");
    }

    @Test
    void allDayEventsUseDateValues() {
        String ics = IcsBuilder.build("T", List.of(IcsBuilder.Event.allDay("c@x", "Application deadline: Dev", null, LocalDate.of(2026, 12, 31))), NOW);

        assertThat(ics).contains("DTSTART;VALUE=DATE:20261231").contains("DTEND;VALUE=DATE:20270101");
    }

    @Test
    void longLinesAreFoldedAt75OctetsWithoutSplittingCharacters() {
        String summary = "Entretien technique avec l'équipe plateforme à Paris, après-midi, prévoir les accès et le matériel ".repeat(3) + "été";
        String ics = IcsBuilder.build("T", List.of(IcsBuilder.Event.timed("d@x", summary, null, null, Instant.parse("2026-11-03T13:30:00Z"), 30, false)), NOW);

        for (String line : ics.split("\r\n")) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
        String unfolded = ics.replace("\r\n ", "");
        assertThat(unfolded).contains("SUMMARY:" + IcsBuilder.escape(summary));
        assertThat(unfolded).doesNotContain("�");
    }

    @Test
    void anEmptyCalendarIsStillValid() {
        assertThat(IcsBuilder.build("T", List.of(), NOW)).contains("BEGIN:VCALENDAR").contains("END:VCALENDAR").doesNotContain("BEGIN:VEVENT");
    }
}
