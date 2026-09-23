package com.sports.analytics.kalshi_epl_engine;

/**
 * Our model's Brier score vs. Kalshi's pre-kickoff price, scored on exactly
 * the same resolved markets. Lower is better. If marketBrier is lower than
 * modelBrier, the market is the better forecaster and we have no real edge.
 *
 * @param resolvedPredictions  all resolved logged predictions
 * @param comparedPredictions  the subset that had a usable pre-kickoff market price
 * @param modelBrier           our Brier score on that subset
 * @param marketBrier          Kalshi's (bid/ask midpoint) Brier score on that subset
 * @param brierDifferenceStandardError standard error of (modelBrier - marketBrier); a gap
 *                                     smaller than about 2x this is still noise
 */
public record MarketComparison(
    int resolvedPredictions,
    int comparedPredictions,
    double modelBrier,
    double marketBrier,
    double brierDifferenceStandardError
) {
}
