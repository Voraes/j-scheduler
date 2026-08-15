package io.github.voraes.jscheduler.spring;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SchedulerDurationParser {
    private static final Pattern SIMPLE = Pattern.compile("^(\\d+)(ns|us|ms|s|m|h|d)$",
            Pattern.CASE_INSENSITIVE);

    private SchedulerDurationParser() { }

    static Duration parse(String value, String attribute) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(attribute + " must not be blank");
        }
        Matcher matcher = SIMPLE.matcher(text);
        try {
            if (!matcher.matches()) {
                Duration parsed = Duration.parse(text);
                if (parsed.isNegative()) {
                    throw new IllegalArgumentException(attribute + " must not be negative");
                }
                return parsed;
            }
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "ns" -> Duration.ofNanos(amount);
                case "us" -> Duration.ofNanos(Math.multiplyExact(amount, 1_000L));
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalStateException("Unsupported duration suffix");
            };
        } catch (ArithmeticException | DateTimeParseException invalid) {
            throw new IllegalArgumentException("Invalid " + attribute + ": " + value, invalid);
        }
    }
}
