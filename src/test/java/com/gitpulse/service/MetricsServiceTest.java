package com.gitpulse.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsServiceTest {

    @Test
    void pullRequestIsOldOnlyWhenOpenForMoreThanFortyEightHours() {
        Instant now = Instant.parse("2026-01-31T12:00:00Z");
        Instant threshold = now.minus(48, ChronoUnit.HOURS);

        assertTrue(MetricsService.isOlderThan48Hours(threshold.minusSeconds(1), now));
        assertFalse(MetricsService.isOlderThan48Hours(threshold, now));
        assertFalse(MetricsService.isOlderThan48Hours(threshold.plusSeconds(1), now));
    }

    @Test
    void branchIsOldOnlyWhenCommitIsMoreThanThirtyDaysOld() {
        Instant now = Instant.parse("2026-01-31T12:00:00Z");
        Instant threshold = now.minus(30, ChronoUnit.DAYS);

        assertTrue(MetricsService.isOlderThan30Days(threshold.minusSeconds(1), now));
        assertFalse(MetricsService.isOlderThan30Days(threshold, now));
        assertFalse(MetricsService.isOlderThan30Days(threshold.plusSeconds(1), now));
    }
}
