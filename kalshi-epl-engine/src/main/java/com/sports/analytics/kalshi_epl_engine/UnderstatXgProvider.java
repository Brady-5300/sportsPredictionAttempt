package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Computes a team's rolling-average xG-for/xG-against from its most recent
 * completed Understat matches, using our own {@link ShotXgCalculator} rather
 * than Understat's precomputed xG values.
 *
 * Results are cached per team since each rating costs one schedule fetch plus
 * one fetch per match in the rolling window (network calls), and this gets
 * called on every live market scan.
 */
@Service
public class UnderstatXgProvider {

    private static final int ROLLING_WINDOW_MATCHES = 6;
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
     * Returns this team's live rolling-average xG rating, or empty if we don't
     * have an Understat slug for the team or it has no completed matches yet
     * this season (e.g. very start of a new season).
     */
    public Optional<TeamXgRating> getRating(String teamName) {
        String slug = teamNameResolver.getUnderstatSlug(teamName);
        if (slug == null) {
            return Optional.empty();
        }

        CacheEntry cached = cache.get(slug);
        if (cached != null && !isExpired(cached)) {
            return cached.rating;
        }

        Optional<TeamXgRating> rating = computeRating(slug);
        cache.put(slug, new CacheEntry(rating, nowSupplier.get()));
        return rating;
    }

    private boolean isExpired(CacheEntry entry) {
        return ChronoUnit.SECONDS.between(entry.fetchedAt, nowSupplier.get()) > CACHE_TTL_SECONDS;
    }

    private Optional<TeamXgRating> computeRating(String slug) {
        int season = currentSeasonStartYear();
        List<UnderstatTeamMatch> matches = scraper.fetchTeamMatches(slug, season);

        List<UnderstatTeamMatch> completed = matches.stream()
            .filter(UnderstatTeamMatch::isResult)
            .sorted(Comparator.comparing(UnderstatTeamMatch::getDatetime).reversed())
            .limit(ROLLING_WINDOW_MATCHES)
            .toList();

        if (completed.isEmpty()) {
            return Optional.empty();
        }

        double totalFor = 0.0;
        double totalAgainst = 0.0;
        int counted = 0;

        for (UnderstatTeamMatch match : completed) {
            Map<String, List<UnderstatShot>> shots = scraper.fetchMatchShots(match.getId());
            boolean weWereHome = "h".equals(match.getSide());

            List<UnderstatShot> ourShots = weWereHome ? shots.get("h") : shots.get("a");
            List<UnderstatShot> theirShots = weWereHome ? shots.get("a") : shots.get("h");
            if (ourShots == null || theirShots == null) {
                continue;
            }

            totalFor += sumXg(ourShots);
            totalAgainst += sumXg(theirShots);
            counted++;
        }

        if (counted == 0) {
            return Optional.empty();
        }

        return Optional.of(new TeamXgRating(totalFor / counted, totalAgainst / counted, counted));
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

    private record CacheEntry(Optional<TeamXgRating> rating, Instant fetchedAt) {
    }
}
