package dev.hugo.watcher;

import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

public final class NotificationPolicy {
    public enum Kind { WORK_ONLY, ANYTIME }
    public record Window(LocalTime start, LocalTime end) {}
    private static final Pattern WORK_ONLY = Pattern.compile("(^|[^A-Z0-9])JC([^A-Z0-9]|$)");
    private NotificationPolicy() {}
    public static Kind classify(String name) {
        String value = Normalizer.normalize(Objects.toString(name, ""), Normalizer.Form.NFD)
                .replaceAll("[\\u0300-\\u036f]", "").trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (value.isEmpty() || value.matches(".*[@+0-9].*")) return Kind.ANYTIME;
        return WORK_ONLY.matcher(value).find() ? Kind.WORK_ONLY : Kind.ANYTIME;
    }
    public static boolean isWithinWorkHours(Instant instant, ZoneId zone, Set<DayOfWeek> days, List<Window> windows) {
        ZonedDateTime local = instant.atZone(zone);
        return days.contains(local.getDayOfWeek()) && windows.stream()
                .anyMatch(w -> !local.toLocalTime().isBefore(w.start()) && local.toLocalTime().isBefore(w.end()));
    }
}
