package com.sports.analytics.kalshi_epl_engine;

import java.util.List;

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
 *
 * The trade test below only uses markets where our lineup read moved the
 * probability by at least 2 points ("trade signals"). Moves are signed so
 * positive = the market moved the way our lineup read said it should.
 *
 * @param signalMatches                matches with at least one trade signal (the test's sample size)
 * @param targetMatches                matches needed before the verdict is called
 * @param marketsWithPriceBefore       signal markets with a price from just before lineups were seen
 * @param avgMoveBeforeSeenCents       how far the market had already moved our way by the time we saw lineups
 * @param pathAfterLineups             average move our way at each checkpoint after lineups were seen
 * @param flip                         buy at the lineup-time ask, sell at the last pre-kickoff bid, both fees paid
 * @param hold                         buy at the lineup-time ask, hold to settlement, fee paid (settled markets only)
 * @param verdict                      plain-English result of the pre-set decision rule
 */
public record LineupSpeedReport(
    int marketsWithBothPrices,
    int marketsWithSignal,
    double avgMoveTowardSignalCents,
    double standardErrorCents,
    double shareMovingTowardSignal,
    double avgAbsoluteMoveCents,
    int signalMatches,
    int targetMatches,
    int marketsWithPriceBefore,
    double avgMoveBeforeSeenCents,
    double moveBeforeSeenStandardErrorCents,
    List<Checkpoint> pathAfterLineups,
    Trade flip,
    Trade hold,
    String verdict
) {
    /** Average move our way {@code minutesAfterLineups} after lineups were seen, in cents. */
    public record Checkpoint(int minutesAfterLineups, int markets, double avgMoveTowardSignalCents, double standardErrorCents) {
    }

    /** Profit per contract in cents after fees, its match-clustered standard error, and the total over all trades. */
    public record Trade(int trades, double avgProfitCents, double standardErrorCents, double totalProfitCents) {
    }
}
