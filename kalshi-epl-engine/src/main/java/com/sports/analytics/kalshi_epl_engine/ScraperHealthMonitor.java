package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks whether each scraped data source (Understat, FotMob) appears to be
 * genuinely broken - network failures or a response we can no longer parse -
 * as opposed to a source simply having no data for a specific team (e.g. a
 * club we don't have a slug mapping for). Callers use this to decide whether
 * to treat missing data as "this source is down" vs "no data for this team".
 *
 * A single failed request doesn't flip a source to "offline" - a few
 * consecutive failures do, to avoid one transient network blip halting the
 * whole scan. Any success immediately resets the streak.
 */
@Service
public class ScraperHealthMonitor {

    private static final int CONSECUTIVE_FAILURES_TO_DECLARE_OFFLINE = 3;

    private final ConcurrentHashMap<String, AtomicInteger> consecutiveFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastFailureReason = new ConcurrentHashMap<>();

    public void recordSuccess(String source) {
        consecutiveFailures.computeIfAbsent(source, s -> new AtomicInteger()).set(0);
    }

    public void recordFailure(String source, String reason) {
        consecutiveFailures.computeIfAbsent(source, s -> new AtomicInteger()).incrementAndGet();
        lastFailureReason.put(source, reason);
    }

    public boolean isOffline(String source) {
        AtomicInteger count = consecutiveFailures.get(source);
        return count != null && count.get() >= CONSECUTIVE_FAILURES_TO_DECLARE_OFFLINE;
    }

    /** Human-readable reason for the most recent failure, or null if the source has never failed. */
    public String lastFailureReason(String source) {
        return lastFailureReason.get(source);
    }
}
