package io.github.voraes.jscheduler.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SchedulerDurationParserTest {
    @Test
    void parsesSimpleAndIsoDurations() {
        assertEquals(Duration.ofMillis(250), SchedulerDurationParser.parse("250ms", "delay"));
        assertEquals(Duration.ofMinutes(2), SchedulerDurationParser.parse("2m", "delay"));
        assertEquals(Duration.ofSeconds(3), SchedulerDurationParser.parse("PT3S", "delay"));
    }

    @Test
    void rejectsBlankNegativeAndMalformedDurations() {
        assertThrows(IllegalArgumentException.class,
                () -> SchedulerDurationParser.parse(" ", "delay"));
        assertThrows(IllegalArgumentException.class,
                () -> SchedulerDurationParser.parse("-1s", "delay"));
        assertThrows(IllegalArgumentException.class,
                () -> SchedulerDurationParser.parse("soon", "delay"));
    }
}
