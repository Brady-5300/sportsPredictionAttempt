package com.sports.analytics.kalshi_epl_engine;

/**
 * One prediction logged the first time the live app evaluated a market -
 * using the FULL pipeline including FotMob lineup/injury adjustments, which
 * can never be reconstructed for past matches (see ModelValidationService),
 * so this is the only way to ever validate that layer.
 *
 * {@code resolved}/{@code actualOutcome} start false/null and are filled in
 * later by PredictionLogService once Kalshi reports the market as settled.
 */
public record PredictionLogEntry(
    String ticker,
    String matchTitle,
    String marketType,
    String matchDate,
    double predictedProbability,
    String loggedAt,
    boolean resolved,
    Boolean actualOutcome
) {
    public PredictionLogEntry withResolution(boolean actualOutcome) {
        return new PredictionLogEntry(ticker, matchTitle, marketType, matchDate, predictedProbability, loggedAt, true, actualOutcome);
    }
}
