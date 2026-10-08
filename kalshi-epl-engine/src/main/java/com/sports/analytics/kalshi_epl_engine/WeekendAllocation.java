package com.sports.analytics.kalshi_epl_engine;

import java.util.List;

/**
 * How to split a weekend wager across the upcoming weekend's undervalued markets.
 *
 * @param windowEnd      last kickoff included (end of the coming Monday, local time)
 * @param allocatedDollars sum of the suggested amounts (equals the budget when there are picks)
 * @param costDollars    what buying the whole contracts actually costs, fees included
 * @param unspentDollars budget left over because Kalshi only sells whole contracts
 */
public record WeekendAllocation(
    double budgetDollars,
    String windowEnd,
    List<Pick> picks,
    double allocatedDollars,
    double costDollars,
    double unspentDollars
) {
    /**
     * @param sharePercent  this pick's share of the weekend budget
     * @param dollars       budget share in dollars
     * @param contracts     whole contracts that amount buys at the current ask plus fee
     * @param costDollars   what those contracts actually cost, fees included
     * @param payoutDollars what they pay if the outcome happens ($1 each)
     */
    public record Pick(
        String ticker,
        String title,
        String kickoff,
        int buyPriceCents,
        int feeCents,
        String modelProbability,
        String edge,
        double sharePercent,
        double dollars,
        int contracts,
        double costDollars,
        double payoutDollars
    ) {
    }
}
