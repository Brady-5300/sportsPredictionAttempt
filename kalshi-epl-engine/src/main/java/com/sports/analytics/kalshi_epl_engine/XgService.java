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
     * each further adjusted for recent form and confirmed missing players via
     * {@link LineupFormAdjustmentService} - falling back to the static rating
     * table for whichever side has no live Understat data at all.
     */
    public double calculateHomeXG(String homeTeam, String awayTeam) {
        return calculateHomeXG(homeTeam, awayTeam, Optional.empty());
    }

    /**
     * @param matchDate this fixture's kickoff date, if known - when it's today,
     *                   LineupFormAdjustmentService will try to use the confirmed
     *                   starting XI (falling back to injury flags if not posted
     *                   yet) instead of just the long-cached injury-flag check.
     */
    public double calculateHomeXG(String homeTeam, String awayTeam, Optional<LocalDate> matchDate) {
        Optional<TeamXgRating> homeRating = liveRatingFor(homeTeam, awayTeam, true, matchDate);
        Optional<TeamXgRating> awayRating = liveRatingFor(awayTeam, homeTeam, false, matchDate);

        double homeAttack = homeRating.map(TeamXgRating::avgXgFor)
            .orElseGet(() -> getAttackRating(teamNameResolver.normalizeTeamName(homeTeam)));
        double awayDefense = awayRating.map(TeamXgRating::avgXgAgainst)
            .orElseGet(() -> getDefenseRating(teamNameResolver.normalizeTeamName(awayTeam)));

        double xg = (homeAttack * 0.6) + ((2.0 - awayDefense) * 0.4);
        return Math.round(xg * 100.0) / 100.0;
    }

    public double calculateAwayXG(String homeTeam, String awayTeam) {
        return calculateAwayXG(homeTeam, awayTeam, Optional.empty());
    }

    public double calculateAwayXG(String homeTeam, String awayTeam, Optional<LocalDate> matchDate) {
        Optional<TeamXgRating> homeRating = liveRatingFor(homeTeam, awayTeam, true, matchDate);
        Optional<TeamXgRating> awayRating = liveRatingFor(awayTeam, homeTeam, false, matchDate);

        double awayAttack = awayRating.map(TeamXgRating::avgXgFor)
            .orElseGet(() -> getAttackRating(teamNameResolver.normalizeTeamName(awayTeam)));
        double homeDefense = homeRating.map(TeamXgRating::avgXgAgainst)
            .orElseGet(() -> getDefenseRating(teamNameResolver.normalizeTeamName(homeTeam)));

        double xg = (awayAttack * 0.6) + ((2.0 - homeDefense) * 0.4);
        return Math.round(xg * 100.0) / 100.0;
    }

    /**
     * The Understat base rating, refined by lineup strength when API-Football
     * data is available for this team (passes the base rating through
     * unchanged otherwise - see LineupFormAdjustmentService).
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
     * True only when both teams have live Understat-derived ratings available,
     * i.e. the xG figures used were computed from real shot data rather than
     * the static fallback table. Callers can use this to flag which markets
     * were evaluated with real data vs. a rough static prior.
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

    private double getAttackRating(String teamKey) {
        switch (teamKey) {
            case "arsenal": return 1.85;
            case "manchester city": return 2.10;
            case "liverpool": return 1.90;
            case "chelsea": return 1.60;
            case "tottenham": return 1.65;
            case "aston villa": return 1.55;
            case "newcastle": return 1.50;
            case "manchester united": return 1.45;
            case "brighton": return 1.40;
            case "crystal palace": return 1.25;
            case "fulham": return 1.25;
            case "brentford": return 1.30;
            case "bournemouth": return 1.30;
            case "everton": return 1.15;
            case "nottingham forest": return 1.20;
            case "sunderland": return 1.10;
            case "leeds united": return 1.20;
            case "hull city": return 1.05;
            case "coventry": return 1.05;
            case "ipswich town": return 1.00;
            default: return 1.35;
        }
    }

    private double getDefenseRating(String teamKey) {
        switch (teamKey) {
            case "arsenal": return 0.75;
            case "manchester city": return 0.80;
            case "liverpool": return 0.85;
            case "chelsea": return 1.00;
            case "tottenham": return 1.10;
            case "aston villa": return 1.15;
            case "newcastle": return 1.10;
            case "manchester united": return 1.20;
            case "brighton": return 1.20;
            case "crystal palace": return 1.25;
            case "fulham": return 1.30;
            case "brentford": return 1.35;
            case "bournemouth": return 1.40;
            case "everton": return 1.25;
            case "nottingham forest": return 1.30;
            case "sunderland": return 1.40;
            case "leeds united": return 1.35;
            case "hull city": return 1.45;
            case "coventry": return 1.45;
            case "ipswich town": return 1.50;
            default: return 1.25;
        }
    }
}
