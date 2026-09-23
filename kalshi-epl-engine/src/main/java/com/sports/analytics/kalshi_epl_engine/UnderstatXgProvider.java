package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Computes a team's recency-weighted xG-for/xG-against, and each of its
 * players' recent per-90 xG contribution, from its completed Understat
 * matches - using our own {@link ShotXgCalculator} rather than Understat's
 * precomputed xG values.
 *
 * Rating design (window, decay, cross-season history, shrinkage, promoted
 * prior) was chosen by scoring variants on held-out EPL seasons (fit on
 * 2020-23, tested on 2024-26): the old 6-match, current-season-only, 0.75
 * decay window was mostly noise and barely beat predicting league base rates
 * (~0.211 vs ~0.215 Brier), while this setup scores ~0.200.
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

    // Player contributions stay on a short, current-season-only window: they
    // drive lineup-absence detection, where last season's players (possibly
    // since transferred) or a 30-match minutes total would give wrong answers.
    private static final int PLAYER_WINDOW_MATCHES = 6;

    private static final long CACHE_TTL_SECONDS = 6 * 60 * 60; // 6 hours

    private final UnderstatScraperService scraper;
    private final ShotXgCalculator shotXgCalculator;
    private final TeamNameResolver teamNameResolver;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

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
     * Returns this team's live recency-weighted xG rating (spanning this
     * season and last), or empty if we don't have an Understat slug for the
     * team or it has no completed EPL matches in either season (e.g. a
     * promoted team before its first game).
     */
    public Optional<TeamXgRating> getRating(String teamName) {
        return getOrCompute(teamName).rating;
    }

    /**
     * Returns each squad player's recent per-90 xG contribution, keyed by
     * player name as it appears on Understat (for cross-referencing against
     * another data source's injury list by name). Empty if live data isn't
     * available for this team.
     */
    public Map<String, PlayerXgContribution> getPlayerContributions(String teamName) {
        return getOrCompute(teamName).playerContributions;
    }

    private CacheEntry getOrCompute(String teamName) {
        String slug = teamNameResolver.getUnderstatSlug(teamName);
        if (slug == null) {
            return CacheEntry.EMPTY;
        }

        CacheEntry cached = cache.get(slug);
        if (cached != null && !isExpired(cached)) {
            return cached;
        }

        CacheEntry computed = compute(slug);
        cache.put(slug, computed);
        return computed;
    }

    private boolean isExpired(CacheEntry entry) {
        return ChronoUnit.SECONDS.between(entry.fetchedAt, nowSupplier.get()) > CACHE_TTL_SECONDS;
    }

    /**
     * Point-in-time version of {@link #getRating}, for backtesting: only
     * considers matches strictly before {@code asOfDateExclusive}, so a
     * backtest of a past match can never see results from after that match -
     * the same rolling-window/recency-decay logic as the live rating, just
     * computed as if "today" were back then. Not cached (each call is for a
     * different historical date, so a time-based cache wouldn't help) and does
     * not affect the live {@link #getRating} cache in any way.
     */
    public Optional<TeamXgRating> getRatingAsOf(String teamName, LocalDate asOfDateExclusive) {
        String slug = teamNameResolver.getUnderstatSlug(teamName);
        if (slug == null) return Optional.empty();

        return computeFromHistory(loadHistory(slug, seasonStartYearOf(asOfDateExclusive), asOfDateExclusive)).rating;
    }

    private CacheEntry compute(String slug) {
        return computeFromHistory(loadHistory(slug, currentSeasonStartYear(), null));
    }

    /**
     * @param currentSeason  most-recent-first completed matches this season
     * @param previousSeason most-recent-first completed matches from last season
     *                       (empty for a promoted team)
     */
    private record History(List<UnderstatTeamMatch> currentSeason, List<UnderstatTeamMatch> previousSeason) {
        boolean promoted() {
            return previousSeason.isEmpty();
        }
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

        return new History(current, previous);
    }

    private List<UnderstatTeamMatch> completedMostRecentFirst(List<UnderstatTeamMatch> matches) {
        return matches.stream()
            .filter(UnderstatTeamMatch::isResult)
            .filter(m -> m.getDatetime() != null)
            .sorted(Comparator.comparing(UnderstatTeamMatch::getDatetime).reversed())
            .toList();
    }

    private CacheEntry computeFromHistory(History history) {
        List<UnderstatTeamMatch> window = new ArrayList<>(history.currentSeason());
        window.addAll(history.previousSeason());
        if (window.size() > ROLLING_WINDOW_MATCHES) {
            window = window.subList(0, ROLLING_WINDOW_MATCHES);
        }

        double weightedFor = 0.0;
        double weightedAgainst = 0.0;
        double weightSum = 0.0;
        int counted = 0;

        // window is most-recent-first, so its list index IS how many matches
        // back this game is - index 0 gets full weight (decay^0 = 1.0).
        for (int i = 0; i < window.size(); i++) {
            Optional<TeamSideOfMatch> side = sideOf(window.get(i));
            if (side.isEmpty()) continue;

            double weight = Math.pow(RECENCY_DECAY_FACTOR, i);
            weightedFor += weight * sumXg(side.get().ourShots());
            weightedAgainst += weight * sumXg(side.get().theirShots());
            weightSum += weight;
            counted++;
        }

        // No real match data at all -> no rating. The prior only shrinks real
        // data; it is never used on its own as a stand-in for missing data.
        if (counted == 0) {
            return new CacheEntry(Optional.empty(), Map.of(), nowSupplier.get());
        }

        double priorFor = history.promoted() ? PROMOTED_PRIOR_XG_FOR : LEAGUE_AVERAGE_XG;
        double priorAgainst = history.promoted() ? PROMOTED_PRIOR_XG_AGAINST : LEAGUE_AVERAGE_XG;
        double shrunkFor = (weightedFor + PRIOR_PSEUDO_MATCHES * priorFor) / (weightSum + PRIOR_PSEUDO_MATCHES);
        double shrunkAgainst = (weightedAgainst + PRIOR_PSEUDO_MATCHES * priorAgainst) / (weightSum + PRIOR_PSEUDO_MATCHES);

        TeamXgRating rating = new TeamXgRating(shrunkFor, shrunkAgainst, counted);
        Map<String, PlayerXgContribution> contributions = computePlayerContributions(history.currentSeason());

        return new CacheEntry(Optional.of(rating), contributions, nowSupplier.get());
    }

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
            playerIsDefensive.putIfAbsent(player, stat.isDefensivePosition());
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

    private record CacheEntry(Optional<TeamXgRating> rating, Map<String, PlayerXgContribution> playerContributions,
                               Instant fetchedAt) {
        static final CacheEntry EMPTY = new CacheEntry(Optional.empty(), Map.of(), Instant.EPOCH);
    }
}
