package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Computes a team's rolling-average xG-for/xG-against, and each of its
 * players' recent per-90 xG contribution, from its most recent completed
 * Understat matches - using our own {@link ShotXgCalculator} rather than
 * Understat's precomputed xG values.
 *
 * Both are computed together in one pass over the same rolling window (and
 * cached together) since they're derived from the same match fetches - no
 * point hitting the network twice for the same games.
 */
@Service
public class UnderstatXgProvider {

    private static final int ROLLING_WINDOW_MATCHES = 6;
    private static final long CACHE_TTL_SECONDS = 6 * 60 * 60; // 6 hours

    // Exponential recency decay applied to the TEAM rating across the rolling
    // window: the most recent match gets weight 1.0, each match further back
    // is discounted by this factor again (match 2 back -> 0.75^2, etc.), so
    // recent form counts more than form from 5-6 games ago instead of a flat
    // average. Player per-90 contributions are NOT decayed - they're meant to
    // represent a player's typical recent output, not weighted team form.
    private static final double RECENCY_DECAY_FACTOR = 0.75;

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
     * Returns this team's live recency-weighted xG rating, or empty if we don't
     * have an Understat slug for the team or it has no completed matches yet
     * this season (e.g. very start of a new season).
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

        // Understat seasons run July-June; a date before July belongs to the
        // season that started the previous calendar year.
        int season = asOfDateExclusive.getMonthValue() >= 7 ? asOfDateExclusive.getYear() : asOfDateExclusive.getYear() - 1;
        List<UnderstatTeamMatch> matches = scraper.fetchTeamMatches(slug, season);

        List<UnderstatTeamMatch> completed = matches.stream()
            .filter(UnderstatTeamMatch::isResult)
            .filter(m -> m.getDatetime() != null && m.getDatetime().compareTo(asOfDateExclusive.toString()) < 0)
            .sorted(Comparator.comparing(UnderstatTeamMatch::getDatetime).reversed())
            .limit(ROLLING_WINDOW_MATCHES)
            .toList();

        return computeRatingFromMatches(completed).rating;
    }

    private CacheEntry compute(String slug) {
        int season = currentSeasonStartYear();
        List<UnderstatTeamMatch> matches = scraper.fetchTeamMatches(slug, season);

        List<UnderstatTeamMatch> completed = matches.stream()
            .filter(UnderstatTeamMatch::isResult)
            .sorted(Comparator.comparing(UnderstatTeamMatch::getDatetime).reversed())
            .limit(ROLLING_WINDOW_MATCHES)
            .toList();

        return computeRatingFromMatches(completed);
    }

    private CacheEntry computeRatingFromMatches(List<UnderstatTeamMatch> completed) {
        if (completed.isEmpty()) {
            return new CacheEntry(Optional.empty(), Map.of(), nowSupplier.get());
        }

        double weightedFor = 0.0;
        double weightedAgainst = 0.0;
        double weightSum = 0.0;
        int counted = 0;

        Map<String, Double> playerXgTotals = new HashMap<>();
        Map<String, Integer> playerMinutes = new HashMap<>();
        Map<String, Boolean> playerIsDefensive = new HashMap<>();

        // completed is sorted most-recent-first, so its list index IS how many
        // matches back this game is - index 0 gets full weight (decay^0 = 1.0).
        for (int i = 0; i < completed.size(); i++) {
            UnderstatTeamMatch match = completed.get(i);
            UnderstatMatchDetails details = scraper.fetchMatchDetails(match.getId());
            boolean weWereHome = "h".equals(match.getSide());

            List<UnderstatShot> ourShots = weWereHome ? details.shots().get("h") : details.shots().get("a");
            List<UnderstatShot> theirShots = weWereHome ? details.shots().get("a") : details.shots().get("h");
            List<UnderstatPlayerMatchStat> ourRoster = weWereHome ? details.rosters().get("h") : details.rosters().get("a");
            if (ourShots == null || theirShots == null) {
                continue;
            }

            double weight = Math.pow(RECENCY_DECAY_FACTOR, i);
            weightedFor += weight * sumXg(ourShots);
            weightedAgainst += weight * sumXg(theirShots);
            weightSum += weight;
            counted++;

            accumulatePlayerXg(ourShots, playerXgTotals);
            accumulatePlayerMinutes(ourRoster, playerMinutes, playerIsDefensive);
        }

        if (counted == 0 || weightSum == 0.0) {
            return new CacheEntry(Optional.empty(), Map.of(), nowSupplier.get());
        }

        TeamXgRating rating = new TeamXgRating(weightedFor / weightSum, weightedAgainst / weightSum, counted);
        Map<String, PlayerXgContribution> contributions = buildContributions(playerXgTotals, playerMinutes, playerIsDefensive);

        return new CacheEntry(Optional.of(rating), contributions, nowSupplier.get());
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
        LocalDate today = LocalDate.now();
        return today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1;
    }

    private record CacheEntry(Optional<TeamXgRating> rating, Map<String, PlayerXgContribution> playerContributions,
                               Instant fetchedAt) {
        static final CacheEntry EMPTY = new CacheEntry(Optional.empty(), Map.of(), Instant.EPOCH);
    }
}
