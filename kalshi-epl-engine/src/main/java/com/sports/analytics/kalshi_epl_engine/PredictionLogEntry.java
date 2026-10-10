package com.sports.analytics.kalshi_epl_engine;

import java.util.Optional;

/**
 * One market's prediction from the live app, next to Kalshi's price.
 *
 * Two snapshots per market:
 * - The main one is refreshed on every scan until kickoff, then frozen: our
 *   last pre-kickoff view next to the market's last pre-kickoff price.
 * - The "at lineups" one is taken once, the first scan after both confirmed
 *   starting XIs appear. Comparing the two prices shows whether Kalshi was
 *   still moving after lineups came out - i.e. whether reacting quickly to
 *   lineups could beat the market.
 *
 * {@code resolved}/{@code actualOutcome} are filled in once Kalshi settles the market.
 *
 * @param predictedProbability full model, including lineup/injury adjustments
 * @param baseProbability      same model with lineup adjustments switched off
 *                             (the one recommendations use; null if not computed)
 * @param marketBidCents       null when no quote was available, or when the
 *                             kickoff time was unknown (could be an in-play price)
 * @param lineupsSeenAt        when confirmed lineups were first seen (null if never, before kickoff)
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
    Boolean actualOutcome,
    String lineupsSeenAt,
    Double predictedProbabilityAtLineups,
    Double baseProbabilityAtLineups,
    Integer marketBidCentsAtLineups,
    Integer marketAskCentsAtLineups
) {
    public PredictionLogEntry withSnapshot(double predictedProbability, Double baseProbability,
                                           Integer bidCents, Integer askCents, String snapshotAt) {
        return new PredictionLogEntry(ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
            bidCents, askCents, kickoff, loggedAt, snapshotAt, resolved, actualOutcome,
            lineupsSeenAt, predictedProbabilityAtLineups, baseProbabilityAtLineups, marketBidCentsAtLineups, marketAskCentsAtLineups);
    }

    /** Copies the current main snapshot into the "at lineups" snapshot. */
    public PredictionLogEntry withLineupSnapshot() {
        return new PredictionLogEntry(ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
            marketBidCents, marketAskCents, kickoff, loggedAt, snapshotAt, resolved, actualOutcome,
            snapshotAt, predictedProbability, baseProbability, marketBidCents, marketAskCents);
    }

    /** Drops an "at lineups" snapshot that wasn't taken from a real confirmed lineup. */
    public PredictionLogEntry withoutLineupSnapshot() {
        return new PredictionLogEntry(ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
            marketBidCents, marketAskCents, kickoff, loggedAt, snapshotAt, resolved, actualOutcome,
            null, null, null, null, null);
    }

    public PredictionLogEntry withResolution(boolean actualOutcome) {
        return new PredictionLogEntry(ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
            marketBidCents, marketAskCents, kickoff, loggedAt, snapshotAt, true, actualOutcome,
            lineupsSeenAt, predictedProbabilityAtLineups, baseProbabilityAtLineups, marketBidCentsAtLineups, marketAskCentsAtLineups);
    }

    /**
     * The market's implied probability (bid/ask midpoint), or empty if there
     * was no two-sided quote or the spread was too wide to call it a price.
     */
    public Optional<Double> marketMidProbability(int maxSpreadCents) {
        return midpoint(marketBidCents, marketAskCents, maxSpreadCents);
    }

    /** Same, for the price when lineups were first seen. */
    public Optional<Double> marketMidProbabilityAtLineups(int maxSpreadCents) {
        return midpoint(marketBidCentsAtLineups, marketAskCentsAtLineups, maxSpreadCents);
    }

    private static Optional<Double> midpoint(Integer bid, Integer ask, int maxSpreadCents) {
        if (bid == null || ask == null) return Optional.empty();
        if (ask <= bid || ask - bid > maxSpreadCents) return Optional.empty();
        return Optional.of((bid + ask) / 200.0);
    }
}
