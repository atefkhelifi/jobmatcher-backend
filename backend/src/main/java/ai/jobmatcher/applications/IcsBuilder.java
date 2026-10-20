package ai.jobmatcher.applications;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Minimal RFC 5545 writer for the events the tracker produces: timed events, all-day events, and a start-time alarm.
 * Text is escaped, lines are folded at 75 octets and line endings are CRLF, as calendar programs require.
 */
final class IcsBuilder {

    record Event(String uid, String summary, String description, String location, Instant start, Instant end, LocalDate allDay,
            boolean alarm) {

        static Event timed(String uid, String summary, String description, String location, Instant start, int minutes, boolean alarm) {
            return new Event(uid, summary, description, location, start, start.plusSeconds(minutes * 60L), null, alarm);
        }

        static Event allDay(String uid, String summary, String description, LocalDate day) {
            return new Event(uid, summary, description, null, null, null, day, false);
        }
    }

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private IcsBuilder() {
    }

    static String build(String calendarName, List<Event> events, Instant now) {
        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//JobMatcher AI//Tracker//EN");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:PUBLISH");
        line(sb, "X-WR-CALNAME:" + escape(calendarName));
        for (Event e : events) {
            line(sb, "BEGIN:VEVENT");
            line(sb, "UID:" + e.uid());
            line(sb, "DTSTAMP:" + UTC.format(now));
            if (e.allDay() != null) {
                line(sb, "DTSTART;VALUE=DATE:" + DAY.format(e.allDay()));
                line(sb, "DTEND;VALUE=DATE:" + DAY.format(e.allDay().plusDays(1)));
            } else {
                line(sb, "DTSTART:" + UTC.format(e.start()));
                line(sb, "DTEND:" + UTC.format(e.end()));
            }
            line(sb, "SUMMARY:" + escape(e.summary()));
            if (e.description() != null && !e.description().isBlank()) {
                line(sb, "DESCRIPTION:" + escape(e.description()));
            }
            if (e.location() != null && !e.location().isBlank()) {
                line(sb, "LOCATION:" + escape(e.location()));
            }
            if (e.alarm()) {
                line(sb, "BEGIN:VALARM");
                line(sb, "ACTION:DISPLAY");
                line(sb, "DESCRIPTION:" + escape(e.summary()));
                line(sb, "TRIGGER:PT0M");
                line(sb, "END:VALARM");
            }
            line(sb, "END:VEVENT");
        }
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    /** Backslash, semicolon, comma and line breaks must be escaped inside text values. */
    static String escape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    private static void line(StringBuilder out, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 75) {
            out.append(content).append("\r\n");
            return;
        }
        // Fold: continuation lines start with one space, and a multi-byte character is never split.
        int start = 0;
        boolean first = true;
        while (start < content.length()) {
            int limit = first ? 75 : 74;
            int end = start;
            int bytesUsed = 0;
            while (end < content.length()) {
                int cp = content.codePointAt(end);
                int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
                if (bytesUsed + len > limit) {
                    break;
                }
                bytesUsed += len;
                end += Character.charCount(cp);
            }
            out.append(first ? "" : " ").append(content, start, end).append("\r\n");
            start = end;
            first = false;
        }
    }
}
