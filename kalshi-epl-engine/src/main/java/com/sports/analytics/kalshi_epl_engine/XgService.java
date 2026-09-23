package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

@Service
public class XgService {

    private final TeamNameResolver teamNameResolver;
    private final UnderstatXgProvider understatXgProvider;
    private final LineupFormAdjustmentService lineupFormAdjustmentService;

    public XgService(TeamNameResolver teamNameResolver, UnderstatXgProvider understatXgProvider,
                      LineupFormAdjustmentService lineupFormAdjustmentService) {
        this.teamNameResolver = teamNameResolver;
        this.understatXgProvider = understatXgProvider;
        this.lineupFormAdjustmentService = lineupFormAdjustmentService;
    }

    /**
     * Home team's expected goals: their live rolling-average attack (goals scored)
     * combined with the away team's live rolling-average defense (goals conceded) -
     * each further adjusted for lineup strength via {@link LineupFormAdjustmentService}.
     *
     * Empty if either team has no live Understat data (e.g. no slug mapping, or no
     * completed matches yet this season) - there is deliberately no static-table
     * fallback here; callers should skip the market with a clear reason rather than
     * silently substitute a rough guess for real data.
     */
    /** Base prediction with NO lineup/injury adjustment (no fixture to look lineups up for). */
    public Optional<Double> calculateHomeXG(String homeTeam, String awayTeam) {
        return calculateHomeXG(homeTeam, awayTeam, Optional.empty());
    }

    /**
     * @param matchDate this fixture's kickoff date, if known - when it's today,
     *                   LineupFormAdjustmentService will try to use the confirmed
     *                   starting XI (falling back to the unavailable-players list
     *                   if not posted yet) instead of just the cached signal.
     */
    public Optional<Double> calculateHomeXG(String homeTeam, String awayTeam, Optional<LocalDate> matchDate) {
        Optional<TeamXgRating> homeRating = liveRatingFor(homeTeam, awayTeam, true, matchDate);
        Optional<TeamXgRating> awayRating = liveRatingFor(awayTeam, homeTeam, false, matchDate);
        if (homeRating.isEmpty() || awayRating.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(combineAttackDefense(homeRating.get().avgXgFor(), awayRating.get().avgXgAgainst(), true));
    }

    /** Base prediction with NO lineup/injury adjustment (no fixture to look lineups up for). */
    public Optional<Double> calculateAwayXG(String homeTeam, String awayTeam) {
        return calculateAwayXG(homeTeam, awayTeam, Optional.empty());
    }

    public Optional<Double> calculateAwayXG(String homeTeam, String awayTeam, Optional<LocalDate> matchDate) {
        Optional<TeamXgRating> homeRating = liveRatingFor(homeTeam, awayTeam, true, matchDate);
        Optional<TeamXgRating> awayRating = liveRatingFor(awayTeam, homeTeam, false, matchDate);
        if (homeRating.isEmpty() || awayRating.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(combineAttackDefense(awayRating.get().avgXgFor(), homeRating.get().avgXgAgainst(), false));
    }

    /**
     * The core xG formula: a Poisson regression on log(own attack rating),
     * log(opponent's defense rating) and home/away, predicting actual goals
     * scored - i.e. the standard multiplicative model
     * goals = base * attack^a * defense^b * homeBoost. Fit with Newton's
     * method on 3,040 team-matches (EPL 2020-23) using UnderstatXgProvider's
     * rating setup, then checked on held-out 2024-26 matches (Brier ~0.200).
     * Rerun MatchXgModelCalibrationTest to recalibrate.
     *
     * The previous coefficients (0.13 attack / 0.10 defense on a linear
     * scale) came from gradient descent that hadn't converged - they barely
     * let ratings move predictions at all. Exponents near 1 here mean a team
     * rated twice as dangerous really is predicted to score about twice as much.
     * Package-private so ModelValidationService can reuse the exact same formula.
     */
    static double combineAttackDefense(double attack, double defense, boolean isHome) {
        double logRate = -0.427766
            + 1.040119 * Math.log(Math.max(attack, 0.05))
            + 0.851259 * Math.log(Math.max(defense, 0.05))
            + 0.175887 * (isHome ? 1.0 : 0.0);
        double xg = Math.exp(logRate);
        return Math.round(xg * 100.0) / 100.0;
    }

    /**
     * The Understat base rating, refined by lineup strength when FotMob data is
     * available for this team (passes the base rating through unchanged
     * otherwise - see LineupFormAdjustmentService).
     */
    private Optional<TeamXgRating> liveRatingFor(String teamName, String opponentName, boolean isHomeSide,
                                                  Optional<LocalDate> matchDate) {
        return understatXgProvider.getRating(teamName)
            .map(base -> {
                LineupFormAdjustmentService.MatchContext context = matchDate
                    .map(date -> new LineupFormAdjustmentService.MatchContext(opponentName, isHomeSide, date))
                    .orElse(null);
                return lineupFormAdjustmentService.adjust(teamName, base, context);
            });
    }

    /**
     * True only when both teams have live Understat-derived ratings available.
     */
    public boolean hasLiveDataFor(String homeTeam, String awayTeam) {
        return understatXgProvider.getRating(homeTeam).isPresent()
            && understatXgProvider.getRating(awayTeam).isPresent();
    }

    /**
     * Returns the canonical Kalshi ticker code (e.g. "MUN", "FUL") for a team name.
     */
    public String getTeamCode(String teamName) {
        return teamNameResolver.getTeamCode(teamName);
    }

    // Helper to safely parse teams from Kalshi market titles or tickers
    public String[] parseTeamsFromKalshi(String title, String ticker) {
        // Default fallback
        String home = "";
        String away = "";

        try {
            if (title != null && title.contains(" vs ")) {
                // Example title: "Fulham vs Manchester United: Fulham wins"
                String[] mainSplit = title.split(":");
                String matchupPart = mainSplit[0].trim(); // "Fulham vs Manchester United"
                String[] teams = matchupPart.split(" vs ");
                if (teams.length >= 2) {
                    home = teams[0].trim();
                    away = teams[1].trim();
                }
            } else if (ticker != null && ticker.contains("-")) {
                // Fallback to ticker parsing if title isn't available (e.g., "KXEPLGAME-26SEP20FULMUN-FUL")
                String[] parts = ticker.split("-");
                if (parts.length >= 2) {
                    String matchCode = parts[1]; // e.g., "26SEP20FULMUN"
                    // Extract 3-letter codes if embedded at the end of the date code
                    if (matchCode.length() >= 6) {
                        home = matchCode.substring(matchCode.length() - 6, matchCode.length() - 3);
                        away = matchCode.substring(matchCode.length() - 3);
                    }
                }
            }
        } catch (Exception e) {
            // Fallback gracefully if parsing fails
        }

        return new String[] { home, away };
    }
}
