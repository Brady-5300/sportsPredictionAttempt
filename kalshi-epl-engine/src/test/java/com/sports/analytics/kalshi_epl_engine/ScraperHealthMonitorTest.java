package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScraperHealthMonitorTest {

    private final ScraperHealthMonitor monitor = new ScraperHealthMonitor();

    @Test
    void startsOnlineWithNoRecordedFailures() {
        assertFalse(monitor.isOffline("understat"));
        assertNull(monitor.lastFailureReason("understat"));
    }

    @Test
    void oneOrTwoFailuresDoNotDeclareOffline() {
        monitor.recordFailure("understat", "timeout");
        assertFalse(monitor.isOffline("understat"));

        monitor.recordFailure("understat", "timeout");
        assertFalse(monitor.isOffline("understat"));
    }

    @Test
    void threeConsecutiveFailuresDeclareOffline() {
        monitor.recordFailure("understat", "timeout");
        monitor.recordFailure("understat", "timeout");
        monitor.recordFailure("understat", "connection refused");

        assertTrue(monitor.isOffline("understat"));
        assertEquals("connection refused", monitor.lastFailureReason("understat"));
    }

    @Test
    void aSuccessResetsTheFailureStreak() {
        monitor.recordFailure("understat", "timeout");
        monitor.recordFailure("understat", "timeout");
        monitor.recordSuccess("understat");
        monitor.recordFailure("understat", "timeout");
        monitor.recordFailure("understat", "timeout");

        assertFalse(monitor.isOffline("understat"), "streak should have reset after the intervening success");
    }

    @Test
    void differentSourcesAreTrackedIndependently() {
        monitor.recordFailure("understat", "x");
        monitor.recordFailure("understat", "x");
        monitor.recordFailure("understat", "x");

        assertTrue(monitor.isOffline("understat"));
        assertFalse(monitor.isOffline("fotmob"));
    }
}
