package com.sports.analytics.kalshi_epl_engine;

/**
 * Brier score with vs. without the FotMob lineup/injury adjustments, scored
 * on exactly the same resolved markets. Lower is better. If withLineupsBrier
 * isn't lower, the lineup layer isn't helping and should be toned down or
 * switched off.
 *
 * @param resolvedPredictions  all resolved logged predictions
 * @param comparedPredictions  the subset that also has a no-lineup prediction
 * @param adjustedPredictions  how many of those the lineup layer actually changed
 *                             (most matches have no notable absences)
 * @param withLineupsBrier     full model's Brier score on the compared subset
 * @param withoutLineupsBrier  no-lineup model's Brier score on the same subset
 * @param brierDifferenceStandardError standard error of the difference; a gap
 *                                     smaller than about 2x this is still noise
 */
public record LineupComparison(
    int resolvedPredictions,
    int comparedPredictions,
    int adjustedPredictions,
    double withLineupsBrier,
    double withoutLineupsBrier,
    double brierDifferenceStandardError
) {
}
