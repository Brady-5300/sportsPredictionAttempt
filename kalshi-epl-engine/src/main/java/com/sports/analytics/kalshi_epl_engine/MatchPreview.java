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
     * The "More details" panel: this season's numbers only, for people to read.
     * (The win/draw/loss probabilities above use the full model, which also
     * looks at last season and adjusts for opponents.)
     *
     * @param season         null before the team's first match this season
     * @param xgForRank      1 = most xG created per match this season
     * @param xgAgainstRank  1 = least xG conceded per match this season
     */
    public record TeamPanel(String name, UnderstatXgProvider.SeasonStats season, Integer xgForRank, Integer xgAgainstRank,
                            int leagueSize, List<UnderstatXgProvider.RecentMatch> form, List<Threat> threats) {
    }

    /** One of a team's top contributors this season: real goals and assists, plus Understat's xG/xA per 90. */
    public record Threat(String player, int goals, int assists, int minutes, double xgPer90, double xaPer90) {
    }
}
