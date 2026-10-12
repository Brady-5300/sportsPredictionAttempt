package com.sports.analytics.kalshi_epl_engine;

import java.util.ArrayList;
import java.util.List;
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
 * For the lineup-speed test it also keeps Kalshi's price from the scan just
 * before lineups were seen (did the market move before we did?) and at fixed
 * checkpoints after (how long does the market take to catch up?).
 *
 * {@code resolved}/{@code actualOutcome} are filled in once Kalshi settles the market.
 *
 * @param predictedProbability full model, including lineup/injury adjustments
 * @param baseProbability      same model with lineup adjustments switched off
 *                             (the one recommendations use; null if not computed)
 * @param marketBidCents       null when no quote was available, or when the
 *                             kickoff time was unknown (could be an in-play price)
 * @param lineupsSeenAt        when confirmed lineups were first seen (null if never, before kickoff)
 * @param marketBidCentsBeforeLineups price at the scan just before lineups were seen
 *                             (null if that scan was too long before to count)
 * @param pricesAfterLineups   Kalshi's price at checkpoints after lineups were seen
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
    Integer marketAskCentsAtLineups,
    Integer marketBidCentsBeforeLineups,
    Integer marketAskCentsBeforeLineups,
    String priceBeforeLineupsAt,
    List<PriceCheck> pricesAfterLineups
) {
    /** Kalshi's price this many minutes after lineups were first seen. */
    public record PriceCheck(int minutesAfterLineups, Integer bidCents, Integer askCents) {
    }

    public PredictionLogEntry {
        pricesAfterLineups = pricesAfterLineups == null ? List.of() : List.copyOf(pricesAfterLineups);
    }

    /** A new entry with no lineup data yet. */
    public PredictionLogEntry(String ticker, String matchTitle, String marketType, String matchDate,
                              double predictedProbability, Double baseProbability,
                              Integer marketBidCents, Integer marketAskCents, String kickoff,
                              String loggedAt, String snapshotAt, boolean resolved, Boolean actualOutcome,
                              String lineupsSeenAt, Double predictedProbabilityAtLineups, Double baseProbabilityAtLineups,
                              Integer marketBidCentsAtLineups, Integer marketAskCentsAtLineups) {
        this(ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
            marketBidCents, marketAskCents, kickoff, loggedAt, snapshotAt, resolved, actualOutcome,
            lineupsSeenAt, predictedProbabilityAtLineups, baseProbabilityAtLineups,
            marketBidCentsAtLineups, marketAskCentsAtLineups, null, null, null, List.of());
    }

    public PredictionLogEntry withSnapshot(double predictedProbability, Double baseProbability,
                                           Integer bidCents, Integer askCents, String snapshotAt) {
        return new PredictionLogEntry(ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
            bidCents, askCents, kickoff, loggedAt, snapshotAt, resolved, actualOutcome,
            lineupsSeenAt, predictedProbabilityAtLineups, baseProbabilityAtLineups, marketBidCentsAtLineups, marketAskCentsAtLineups,
            marketBidCentsBeforeLineups, marketAskCentsBeforeLineups, priceBeforeLineupsAt, pricesAfterLineups);
    }

    /**
     * Copies the current main snapshot into the "at lineups" snapshot, and
     * records the price from the scan before (pass nulls if there wasn't a recent one).
     */
    public PredictionLogEntry withLineupSnapshot(Integer bidBefore, Integer askBefore, String beforeAt) {
        return new PredictionLogEntry(ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
            marketBidCents, marketAskCents, kickoff, loggedAt, snapshotAt, resolved, actualOutcome,
            snapshotAt, predictedProbability, baseProbability, marketBidCents, marketAskCents,
            bidBefore, askBefore, beforeAt, List.of());
    }

    /** Drops an "at lineups" snapshot that wasn't taken from a real confirmed lineup. */
    public PredictionLogEntry withoutLineupSnapshot() {
        return new PredictionLogEntry(ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
            marketBidCents, marketAskCents, kickoff, loggedAt, snapshotAt, resolved, actualOutcome,
            null, null, null, null, null, null, null, null, List.of());
    }

    /** Adds one post-lineup price checkpoint. */
    public PredictionLogEntry withPriceCheck(int minutesAfterLineups, Integer bidCents, Integer askCents) {
        List<PriceCheck> path = new ArrayList<>(pricesAfterLineups);
        path.add(new PriceCheck(minutesAfterLineups, bidCents, askCents));
        return new PredictionLogEntry(ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
            marketBidCents, marketAskCents, kickoff, loggedAt, snapshotAt, resolved, actualOutcome,
            lineupsSeenAt, predictedProbabilityAtLineups, baseProbabilityAtLineups, marketBidCentsAtLineups, marketAskCentsAtLineups,
            marketBidCentsBeforeLineups, marketAskCentsBeforeLineups, priceBeforeLineupsAt, path);
    }

    public PredictionLogEntry withResolution(boolean actualOutcome) {
        return new PredictionLogEntry(ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
            marketBidCents, marketAskCents, kickoff, loggedAt, snapshotAt, true, actualOutcome,
            lineupsSeenAt, predictedProbabilityAtLineups, baseProbabilityAtLineups, marketBidCentsAtLineups, marketAskCentsAtLineups,
            marketBidCentsBeforeLineups, marketAskCentsBeforeLineups, priceBeforeLineupsAt, pricesAfterLineups);
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

    /** Same, for the price just before lineups were seen. */
    public Optional<Double> marketMidProbabilityBeforeLineups(int maxSpreadCents) {
        return midpoint(marketBidCentsBeforeLineups, marketAskCentsBeforeLineups, maxSpreadCents);
    }

    static Optional<Double> midpoint(Integer bid, Integer ask, int maxSpreadCents) {
        if (bid == null || ask == null) return Optional.empty();
        if (ask <= bid || ask - bid > maxSpreadCents) return Optional.empty();
        return Optional.of((bid + ask) / 200.0);
    }
}
