package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * Computes every team's recency-weighted, opponent-adjusted xG-for/xG-against
 * ratings, and each team's players' recent per-90 xG contribution, from
 * completed Understat matches - using our own {@link ShotXgCalculator} rather
 * than Understat's precomputed xG values.
 *
 * Rating design (window, decay, cross-season history, shrinkage, promoted
 * prior, opponent adjustment) was chosen by scoring variants on held-out EPL
 * seasons (fit on 2020-23, tested on 2024-26, ~800 matches):
 * - the original 6-match, current-season-only window: ~0.211 Brier
 * - 30 matches across two seasons, shrunk toward a prior: 0.2002
 * - plus opponent adjustment: 0.1994 (better in every test season; the
 *   bookmakers' closing odds score 0.1983 on the same matches)
 *
 * Opponent adjustment: 1.5 xG created against a stingy defence counts for
 * more than 1.5 xG against a leaky one. Each match's xG is scaled by how good
 * the opponent is relative to the league average, and since opponents'
 * ratings depend on their own opponents, the whole league is re-rated a few
 * times until the numbers settle. That's why ratings are computed for the
 * whole league at once, not one team at a time.
 */
@Service
public class UnderstatXgProvider {

    // Long window with gentle decay: a single match's xG is very noisy, so
    // 6 heavily-decayed matches (effective sample ~3) mostly measured luck.
    private static final int ROLLING_WINDOW_MATCHES = 30;
    private static final double RECENCY_DECAY_FACTOR = 0.96;

    // Ratings are shrunk toward a prior worth this many matches, so a team
    // with 2 games played isn't rated on 2 games alone.
    private static final double PRIOR_PSEUDO_MATCHES = 3.0;
    private static final double LEAGUE_AVERAGE_XG = 1.40;
    // Average first-season xG for/against of promoted EPL teams (2020-23).
    // Understat doesn't cover the Championship, so a promoted team has no
    // prior-season data and the league average would badly overrate it.
    private static final double PROMOTED_PRIOR_XG_FOR = 1.14;
    private static final double PROMOTED_PRIOR_XG_AGAINST = 1.87;

    // Re-rating passes for the opponent adjustment; the numbers settle well within this.
    private static final int OPPONENT_ADJUSTMENT_ITERATIONS = 5;

    // Player contributions stay on a short, current-season-only window: they
    // drive lineup-absence detection, where last season's players (possibly
    // since transferred) or a 30-match minutes total would give wrong answers.
    private static final int PLAYER_WINDOW_MATCHES = 6;

    private static final long CACHE_TTL_SECONDS = 6 * 60 * 60; // 6 hours
    private static final int AS_OF_CACHE_SIZE = 64;

    private final UnderstatScraperService scraper;
    private final ShotXgCalculator shotXgCalculator;
    private final TeamNameResolver teamNameResolver;

    // Live league ratings, keyed by Understat slug, refreshed as a whole.
    private LeagueSnapshot liveSnapshot;
    // Point-in-time league ratings for backtesting, keyed by "as of" date.
    private final Map<LocalDate, AsOfRatings> asOfCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<LocalDate, AsOfRatings> eldest) {
            return size() > AS_OF_CACHE_SIZE;
        }
    };

    // Overridable in tests so cache expiry can be exercised without sleeping.
    Supplier<Instant> nowSupplier = Instant::now;

    public UnderstatXgProvider(UnderstatScraperService scraper,
                                ShotXgCalculator shotXgCalculator,
                                TeamNameResolver teamNameResolver) {
        this.scraper = scraper;
        this.shotXgCalculator = shotXgCalculator;
        this.teamNameResolver = teamNameResolver;
    }

    /**
     * Returns this team's live rating (spanning this season and last), or
     * empty if we don't have an Understat slug for the team or it has no
     * completed EPL matches in either season (e.g. a promoted team before
     * its first game).
     */
    public Optional<TeamXgRating> getRating(String teamName) {
        String slug = teamNameResolver.getUnderstatSlug(teamName);
        if (slug == null) return Optional.empty();
        return Optional.ofNullable(liveSnapshot(slug).ratings().get(slug));
    }

    /**
     * Returns each squad player's recent per-90 xG contribution, keyed by
     * player name as it appears on Understat (for cross-referencing against
     * another data source's injury list by name). Empty if live data isn't
     * available for this team.
     */
    public Map<String, PlayerXgContribution> getPlayerContributions(String teamName) {
        String slug = teamNameResolver.getUnderstatSlug(teamName);
        if (slug == null) return Map.of();
        return liveSnapshot(slug).playerContributions().getOrDefault(slug, Map.of());
    }

    /**
     * Point-in-time version of {@link #getRating}, for backtesting: only
     * considers matches strictly before {@code asOfDateExclusive} (for every
     * team, including opponents used in the adjustment), so a backtest of a
     * past match can never see results from after that match.
     */
    public synchronized Optional<TeamXgRating> getRatingAsOf(String teamName, LocalDate asOfDateExclusive) {
        String slug = teamNameResolver.getUnderstatSlug(teamName);
        if (slug == null) return Optional.empty();

        AsOfRatings cached = asOfCache.get(asOfDateExclusive);
        if (cached == null || !cached.covered().contains(slug)) {
            Set<String> covered = new LinkedHashSet<>();
            Map<String, History> histories = loadHistories(seasonStartYearOf(asOfDateExclusive), asOfDateExclusive, slug, covered);
            cached = new AsOfRatings(rateLeague(histories), covered);
            asOfCache.put(asOfDateExclusive, cached);
        }
        return Optional.ofNullable(cached.ratings().get(slug));
    }

    /** @param covered every team this calculation looked at - including ones with no matches, and so no rating */
    private record AsOfRatings(Map<String, TeamXgRating> ratings, Set<String> covered) {
    }

    private record LeagueSnapshot(Map<String, TeamXgRating> ratings,
                                  Map<String, Map<String, PlayerXgContribution>> playerContributions,
                                  Set<String> covered,
                                  Instant fetchedAt) {
    }

    private synchronized LeagueSnapshot liveSnapshot(String requiredSlug) {
        boolean fresh = liveSnapshot != null
            && ChronoUnit.SECONDS.between(liveSnapshot.fetchedAt(), nowSupplier.get()) <= CACHE_TTL_SECONDS
            && liveSnapshot.covered().contains(requiredSlug);
        if (fresh) return liveSnapshot;

        Set<String> covered = new LinkedHashSet<>();
        Map<String, History> histories = loadHistories(currentSeasonStartYear(), null, requiredSlug, covered);
        Map<String, Map<String, PlayerXgContribution>> contributions = new HashMap<>();
        histories.forEach((slug, history) -> contributions.put(slug, computePlayerContributions(history.currentSeason())));
        liveSnapshot = new LeagueSnapshot(rateLeague(histories), contributions, covered, nowSupplier.get());
        return liveSnapshot;
    }

    // ---------------------------------------------------------------- history

    /** One team's side of one match: our xG, their xG, and who they were (null if unknown to us). */
    private record MatchXg(double xgFor, double xgAgainst, String opponentSlug) {
    }

    /**
     * @param window        up to 30 most-recent-first matches with shot data, this season then last
     * @param currentSeason most-recent-first completed matches this season (for player contributions)
     * @param promoted      no EPL matches last season
     */
    private record History(List<MatchXg> window, List<UnderstatTeamMatch> currentSeason, boolean promoted) {
    }

    /**
     * Histories for every team in the league this season and last (opponents
     * need rating too), plus the required team. Fills {@code slugs} with every
     * team looked at.
     */
    private Map<String, History> loadHistories(int season, LocalDate beforeExclusive, String requiredSlug, Set<String> slugs) {
        slugs.add(requiredSlug);
        for (int s : new int[]{season, season - 1}) {
            for (String title : scraper.fetchLeagueTeamTitles(s)) {
                String slug = teamNameResolver.getUnderstatSlug(title);
                if (slug != null) slugs.add(slug);
            }
        }

        Map<String, History> histories = new LinkedHashMap<>();
        for (String slug : slugs) {
            History history = loadHistory(slug, season, beforeExclusive);
            if (!history.window().isEmpty()) histories.put(slug, history);
        }
        return histories;
    }

    private History loadHistory(String slug, int season, LocalDate beforeExclusive) {
        String seasonStart = season + "-07-01";
        String previousSeasonStart = (season - 1) + "-07-01";

        List<UnderstatTeamMatch> current = completedMostRecentFirst(scraper.fetchTeamMatches(slug, season)).stream()
            .filter(m -> m.getDatetime().compareTo(seasonStart) >= 0)
            .filter(m -> beforeExclusive == null || m.getDatetime().compareTo(beforeExclusive.toString()) < 0)
            .toList();

        // Understat answers a request for a season the team wasn't in the league
        // with a DIFFERENT season's data (e.g. a promoted team's "last season"
        // comes back as this season), so filter by date rather than trusting it.
        List<UnderstatTeamMatch> previous = completedMostRecentFirst(scraper.fetchTeamMatches(slug, season - 1)).stream()
            .filter(m -> m.getDatetime().compareTo(previousSeasonStart) >= 0)
            .filter(m -> m.getDatetime().compareTo(seasonStart) < 0)
            .toList();

        List<MatchXg> window = new ArrayList<>();
        List<UnderstatTeamMatch> all = new ArrayList<>(current);
        all.addAll(previous);
        for (UnderstatTeamMatch match : all) {
            if (window.size() >= ROLLING_WINDOW_MATCHES) break;
            Optional<TeamSideOfMatch> side = sideOf(match);
            if (side.isEmpty()) continue;
            String opponentTitle = "h".equals(match.getSide()) ? match.getAwayTeamTitle() : match.getHomeTeamTitle();
            window.add(new MatchXg(sumXg(side.get().ourShots()), sumXg(side.get().theirShots()),
                opponentTitle == null ? null : teamNameResolver.getUnderstatSlug(opponentTitle)));
        }
        return new History(window, current, previous.isEmpty());
    }

    private List<UnderstatTeamMatch> completedMostRecentFirst(List<UnderstatTeamMatch> matches) {
        return matches.stream()
            .filter(UnderstatTeamMatch::isResult)
            .filter(m -> m.getDatetime() != null)
            .sorted(Comparator.comparing(UnderstatTeamMatch::getDatetime).reversed())
            .toList();
    }

    // ---------------------------------------------------------------- ratings

    /**
     * Rates every team: first from raw xG, then repeatedly re-rates with each
     * match's xG scaled by the opponent's current rating. A team with no real
     * matches has no history entry, so it never gets a rating - the prior only
     * shrinks real data, it never stands in for missing data.
     */
    private Map<String, TeamXgRating> rateLeague(Map<String, History> histories) {
        Map<String, TeamXgRating> ratings = new HashMap<>();
        histories.forEach((slug, history) -> ratings.put(slug, shrunkRating(history, m -> m.xgFor(), m -> m.xgAgainst())));

        for (int iteration = 0; iteration < OPPONENT_ADJUSTMENT_ITERATIONS; iteration++) {
            Map<String, TeamXgRating> previous = Map.copyOf(ratings);
            histories.forEach((slug, history) -> ratings.put(slug, shrunkRating(history,
                m -> {
                    TeamXgRating opponent = m.opponentSlug() == null ? null : previous.get(m.opponentSlug());
                    return opponent == null ? m.xgFor() : m.xgFor() * LEAGUE_AVERAGE_XG / opponent.avgXgAgainst();
                },
                m -> {
                    TeamXgRating opponent = m.opponentSlug() == null ? null : previous.get(m.opponentSlug());
                    return opponent == null ? m.xgAgainst() : m.xgAgainst() * LEAGUE_AVERAGE_XG / opponent.avgXgFor();
                })));
        }
        return ratings;
    }

    private TeamXgRating shrunkRating(History history,
                                      ToDoubleFunction<MatchXg> xgFor,
                                      ToDoubleFunction<MatchXg> xgAgainst) {
        double weightedFor = 0.0;
        double weightedAgainst = 0.0;
        double weightSum = 0.0;
        // window is most-recent-first, so its list index IS how many matches
        // back this game is - index 0 gets full weight (decay^0 = 1.0).
        for (int i = 0; i < history.window().size(); i++) {
            MatchXg match = history.window().get(i);
            double weight = Math.pow(RECENCY_DECAY_FACTOR, i);
            weightedFor += weight * xgFor.applyAsDouble(match);
            weightedAgainst += weight * xgAgainst.applyAsDouble(match);
            weightSum += weight;
        }

        double priorFor = history.promoted() ? PROMOTED_PRIOR_XG_FOR : LEAGUE_AVERAGE_XG;
        double priorAgainst = history.promoted() ? PROMOTED_PRIOR_XG_AGAINST : LEAGUE_AVERAGE_XG;
        return new TeamXgRating(
            (weightedFor + PRIOR_PSEUDO_MATCHES * priorFor) / (weightSum + PRIOR_PSEUDO_MATCHES),
            (weightedAgainst + PRIOR_PSEUDO_MATCHES * priorAgainst) / (weightSum + PRIOR_PSEUDO_MATCHES),
            history.window().size());
    }

    // ---------------------------------------------------------------- players

    private Map<String, PlayerXgContribution> computePlayerContributions(List<UnderstatTeamMatch> currentSeason) {
        Map<String, Double> playerXgTotals = new HashMap<>();
        Map<String, Integer> playerMinutes = new HashMap<>();
        Map<String, Boolean> playerIsDefensive = new HashMap<>();

        for (UnderstatTeamMatch match : currentSeason.subList(0, Math.min(PLAYER_WINDOW_MATCHES, currentSeason.size()))) {
            Optional<TeamSideOfMatch> side = sideOf(match);
            if (side.isEmpty()) continue;

            accumulatePlayerXg(side.get().ourShots(), playerXgTotals);
            accumulatePlayerMinutes(side.get().ourRoster(), playerMinutes, playerIsDefensive);
        }

        return buildContributions(playerXgTotals, playerMinutes, playerIsDefensive);
    }

    private record TeamSideOfMatch(List<UnderstatShot> ourShots, List<UnderstatShot> theirShots,
                                   List<UnderstatPlayerMatchStat> ourRoster) {
    }

    /** This team's side of a match, or empty if the match's shot data is missing. */
    private Optional<TeamSideOfMatch> sideOf(UnderstatTeamMatch match) {
        UnderstatMatchDetails details = scraper.fetchMatchDetails(match.getId());
        String us = "h".equals(match.getSide()) ? "h" : "a";
        String them = "h".equals(us) ? "a" : "h";
        List<UnderstatShot> ourShots = details.shots().get(us);
        List<UnderstatShot> theirShots = details.shots().get(them);
        if (ourShots == null || theirShots == null) return Optional.empty();
        return Optional.of(new TeamSideOfMatch(ourShots, theirShots, details.rosters().get(us)));
    }

    private void accumulatePlayerXg(List<UnderstatShot> shots, Map<String, Double> playerXgTotals) {
        for (UnderstatShot shot : shots) {
            String player = shot.getPlayer();
            if (player == null) continue;
            playerXgTotals.merge(player, shotXgCalculator.calculateXg(shot), Double::sum);
        }
    }

    private void accumulatePlayerMinutes(List<UnderstatPlayerMatchStat> roster,
                                          Map<String, Integer> playerMinutes,
                                          Map<String, Boolean> playerIsDefensive) {
        if (roster == null) return;
        for (UnderstatPlayerMatchStat stat : roster) {
            String player = stat.getPlayer();
            if (player == null) continue;
            playerMinutes.merge(player, stat.minutesPlayed(), Integer::sum);
            // A substitute appearance is listed as position "Sub", which says nothing about the player's role.
            if (!"Sub".equals(stat.getPosition())) {
                playerIsDefensive.putIfAbsent(player, stat.isDefensivePosition());
            }
        }
    }

    private Map<String, PlayerXgContribution> buildContributions(Map<String, Double> playerXgTotals,
                                                                   Map<String, Integer> playerMinutes,
                                                                   Map<String, Boolean> playerIsDefensive) {
        Map<String, PlayerXgContribution> result = new HashMap<>();
        // Union of both maps: a player might have minutes with zero shots (defender),
        // or - shouldn't normally happen, but be defensive - shots with no roster entry.
        for (String player : playerMinutes.keySet()) {
            double xg = playerXgTotals.getOrDefault(player, 0.0);
            int minutes = playerMinutes.get(player);
            boolean defensive = playerIsDefensive.getOrDefault(player, false);
            result.put(player, new PlayerXgContribution(player, defensive, xg, minutes));
        }
        for (String player : playerXgTotals.keySet()) {
            result.putIfAbsent(player, new PlayerXgContribution(player, false, playerXgTotals.get(player), 0));
        }
        return result;
    }

    private double sumXg(List<UnderstatShot> shots) {
        double sum = 0.0;
        for (UnderstatShot shot : shots) {
            sum += shotXgCalculator.calculateXg(shot);
        }
        return sum;
    }

    /** Understat identifies a season by its starting year (2025 = the 2025/26 season). */
    int currentSeasonStartYear() {
        return seasonStartYearOf(LocalDate.ofInstant(nowSupplier.get(), ZoneOffset.UTC));
    }

    /** Seasons run July-June; a date before July belongs to the season that started the previous year. */
    static int seasonStartYearOf(LocalDate date) {
        return date.getMonthValue() >= 7 ? date.getYear() : date.getYear() - 1;
    }
}
