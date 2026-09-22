package com.sports.analytics.kalshi_epl_engine;

import java.util.List;

/**
 * Result of a market scan. When {@code status} is not "OK", {@code evaluations}
 * is always empty - a broken core data source (Understat) means we don't trust
 * ANY evaluation from this scan, rather than silently mixing in stale/static
 * numbers. {@code warning} carries a non-fatal issue (e.g. FotMob down, so
 * lineup/injury adjustments weren't applied) alongside otherwise-normal results.
 */
public record MarketScanResult(
    String status,
    String message,
    String warning,
    List<MarketEvaluation> evaluations,
    List<SkippedMarket> skipped
) {
    public static final String STATUS_OK = "OK";
    public static final String STATUS_UNDERSTAT_OFFLINE = "UNDERSTAT_OFFLINE";

    public static MarketScanResult offline(String source, String reason) {
        String message = "[" + source.toUpperCase() + "] scraper appears offline"
            + (reason != null ? " (" + reason + ")" : "")
            + " - halting scan until it recovers rather than falling back to static ratings.";
        return new MarketScanResult(STATUS_UNDERSTAT_OFFLINE, message, null, List.of(), List.of());
    }

    public static MarketScanResult ok(String warning, List<MarketEvaluation> evaluations, List<SkippedMarket> skipped) {
        return new MarketScanResult(STATUS_OK, null, warning, evaluations, skipped);
    }
}
