package com.sports.analytics.kalshi_epl_engine;

/**
 * Does Kalshi keep moving after lineups come out, in the direction our
 * lineup adjustment pointed? If so, reacting to lineups quickly beats the
 * market; if prices have already adjusted when we first see the lineups,
 * there's no speed edge.
 *
 * "Signal" = our lineup-adjusted probability minus our no-lineup probability,
 * at the moment lineups were first seen. "Move" = Kalshi's last pre-kickoff
 * midpoint minus its midpoint at that moment.
 *
 * @param marketsWithBothPrices        markets with usable prices at lineups and at kickoff
 * @param marketsWithSignal            those where our lineup adjustment moved the probability by at least 1 point
 * @param avgMoveTowardSignalCents     average market move in the signal's direction, in cents
 *                                     (positive = the market followed our lineup read after we saw it)
 * @param standardErrorCents           standard error of that average, treating each match as one unit
 * @param shareMovingTowardSignal      share of signal markets whose price moved in the signal's direction
 * @param avgAbsoluteMoveCents         average size of the post-lineup move, across all markets with both prices
 */
public record LineupSpeedReport(
    int marketsWithBothPrices,
    int marketsWithSignal,
    double avgMoveTowardSignalCents,
    double standardErrorCents,
    double shareMovingTowardSignal,
    double avgAbsoluteMoveCents
) {
}
