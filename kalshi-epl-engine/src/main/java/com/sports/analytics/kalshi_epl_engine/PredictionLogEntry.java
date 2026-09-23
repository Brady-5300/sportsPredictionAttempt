package com.sports.analytics.kalshi_epl_engine;

import java.util.Optional;

/**
 * One market's prediction from the live app, using the FULL pipeline
 * including FotMob lineup/injury adjustments (which can't be reconstructed
 * for past matches, so this is the only way to validate that layer).
 *
 * The prediction and Kalshi's bid/ask are captured together and refreshed on
 * every scan until kickoff, then frozen - so what's kept is our last
 * pre-kickoff view (after lineups are posted) next to the market's price at
 * that same moment. {@code resolved}/{@code actualOutcome} are filled in once
 * Kalshi settles the market.
 *
 * @param predictedProbability full model, including lineup/injury adjustments
 * @param baseProbability      same model with lineup adjustments switched off
 *                             (null if it couldn't be computed)
 * @param marketBidCents/marketAskCents null when no quote was available, or
 *                                      when the kickoff time was unknown (the
 *                                      price might then be an in-play one)
 */
public record PredictionLogEntry(
    String ticker,
    String matchTitle,
    String marketType,
    String matchDate,
    double predictedProbability,
    Double baseProbability,
    Integer marketBidCents,
    Integer marketAskCents,
    String kickoff,
    String loggedAt,
    String snapshotAt,
    boolean resolved,
    Boolean actualOutcome
) {
    public PredictionLogEntry withSnapshot(double predictedProbability, Double baseProbability,
                                           Integer bidCents, Integer askCents, String snapshotAt) {
        return new PredictionLogEntry(ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
            bidCents, askCents, kickoff, loggedAt, snapshotAt, resolved, actualOutcome);
    }

    public PredictionLogEntry withResolution(boolean actualOutcome) {
        return new PredictionLogEntry(ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
            marketBidCents, marketAskCents, kickoff, loggedAt, snapshotAt, true, actualOutcome);
    }

    /**
     * The market's implied probability (bid/ask midpoint), or empty if there
     * was no two-sided quote or the spread was too wide to call it a price.
     */
    public Optional<Double> marketMidProbability(int maxSpreadCents) {
        if (marketBidCents == null || marketAskCents == null) return Optional.empty();
        if (marketAskCents <= marketBidCents || marketAskCents - marketBidCents > maxSpreadCents) return Optional.empty();
        return Optional.of((marketBidCents + marketAskCents) / 200.0);
    }
}
