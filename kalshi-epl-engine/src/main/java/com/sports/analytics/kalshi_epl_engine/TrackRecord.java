package com.sports.analytics.kalshi_epl_engine;

import java.util.List;

/**
 * Everything the "Track record" and "Model vs Kalshi" tabs show, built from
 * the live prediction log's finished matches. "Model" means the base model
 * (no lineup adjustment) - the one recommendations use.
 *
 * @param overall     model vs. Kalshi on every finished market with a usable pre-kickoff price
 * @param calibration when the model said X%, how often it happened
 * @param picks       how the app's YES picks would have done at $1 each
 * @param matches     finished matches, most recent first
 * @param weeks       weekend-by-weekend scoreboard, most recent first
 */
public record TrackRecord(
    int finishedMatches,
    MarketComparison overall,
    List<CalibrationBucket> calibration,
    PickRecord picks,
    List<Match> matches,
    List<Week> weeks,
    int modelWeeks,
    int kalshiWeeks,
    int tiedWeeks
) {
    /** "Bet $1 on every YES pick at the last pre-kickoff ask plus fee." */
    public record PickRecord(int picks, int wins, double staked, double returned, double profit) {
    }

    /** One finished match. Brier scores are null when Kalshi had no usable price to compare. */
    public record Match(String name, String kickoff, String result, List<Market> markets,
                       Double modelBrier, Double kalshiBrier, String winner) {
    }

    /** One outcome of a match, e.g. "Leeds United wins". kalshiProbability is null without a usable price. */
    public record Market(String label, double modelProbability, Double kalshiProbability, boolean happened, boolean pick) {
    }

    /** One weekend (Tuesday through Monday), labelled by its Monday. */
    public record Week(String weekendEnding, int matches, double modelBrier, double kalshiBrier, String winner) {
    }
}
