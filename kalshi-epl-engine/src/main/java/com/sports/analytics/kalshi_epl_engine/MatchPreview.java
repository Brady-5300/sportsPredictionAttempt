package com.sports.analytics.kalshi_epl_engine;

import java.util.List;

/**
 * Everything the "Match previews" tab shows for one upcoming match. Model
 * numbers come from the base model (no lineup adjustment), the same one the
 * app's picks use. Kalshi probabilities are null when there's no usable price.
 */
public record MatchPreview(
    String home,
    String away,
    String kickoff,
    double homeWin,
    double draw,
    double awayWin,
    Double kalshiHomeWin,
    Double kalshiDraw,
    Double kalshiAwayWin,
    double homeExpectedGoals,
    double awayExpectedGoals,
    List<Scoreline> likelyScores,
    double bothTeamsScore,
    double overTwoAndHalfGoals,
    TeamPanel homeTeam,
    TeamPanel awayTeam
) {
    public record Scoreline(int home, int away, double probability) {
    }

    /**
     * @param attack       opponent-adjusted xG created per match
     * @param defense      opponent-adjusted xG conceded per match (lower is better)
     * @param attackRank   1 = best attack in the league
     * @param defenseRank  1 = best defence in the league
     */
    public record TeamPanel(String name, double attack, double defense, Integer attackRank, Integer defenseRank,
                            int leagueSize, List<UnderstatXgProvider.RecentMatch> form, List<Threat> threats) {
    }

    /** One of a team's most dangerous attackers this season, by expected goals per 90 minutes. */
    public record Threat(String player, double xgPer90, double totalXg, int minutes) {
    }
}
