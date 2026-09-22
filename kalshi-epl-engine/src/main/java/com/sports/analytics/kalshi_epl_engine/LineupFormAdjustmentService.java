package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Adjusts a team's base Understat xG rating for confirmed missing players,
 * using API-Football's squad/injury data ({@link ApiFootballClient}) cross
 * referenced against each player's own recent per-90 xG contribution
 * (computed from Understat shot data, see UnderstatXgProvider).
 *
 * The free API-Football tier is capped at 100 requests/MONTH (a hard limit),
 * so this caches long by default and only refreshes sooner when the match is
 * happening today - see {@link #adjust(String, TeamXgRating, boolean)}.
 *
 * If API-Football isn't available (no key, team not resolvable, request
 * failure), this passes the base Understat rating through unchanged rather
 * than failing the whole evaluation - it's a refinement layer, not a
 * requirement for "live data". Recent-form weighting is handled entirely by
 * UnderstatXgProvider's recency-decayed rolling window - there's no cheap
 * "last N results" endpoint on this API, so it isn't duplicated here.
 */
@Service
public class LineupFormAdjustmentService {

    // Missing defensive players don't have a clean "goals prevented" stat from
    // shot data alone, so each confirmed-out GK/DEF gets a flat xG-against bump
    // instead of a per-player-measured one.
    private static final double DEFENSIVE_ABSENCE_PENALTY = 0.12;

    // Long default cache: the free tier's 100-requests/month cap means we can't
    // afford to refresh often for matches that aren't imminent.
    private static final long DEFAULT_CACHE_TTL_SECONDS = 4L * 24 * 60 * 60; // 4 days
    // When the match is happening today, refresh far more aggressively so
    // last-minute injury news is reflected - but still not on every single call.
    private static final long IMMINENT_CACHE_TTL_SECONDS = 30 * 60; // 30 minutes

    private final ApiFootballClient apiFootballClient;
    private final UnderstatXgProvider understatXgProvider;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    Supplier<Instant> nowSupplier = Instant::now;

    public LineupFormAdjustmentService(ApiFootballClient apiFootballClient, UnderstatXgProvider understatXgProvider) {
        this.apiFootballClient = apiFootballClient;
        this.understatXgProvider = understatXgProvider;
    }

    /**
     * Adjusts the given base rating for confirmed missing players. Returns the
     * base rating unchanged if API-Football data isn't available for this team.
     *
     * @param matchIsToday whether the match this rating is being used for kicks
     *                     off today - if true, a much shorter cache TTL is used
     *                     so injury news close to kickoff isn't missed, at the
     *                     cost of spending more of the monthly request quota.
     */
    public TeamXgRating adjust(String teamName, TeamXgRating base, boolean matchIsToday) {
        CacheEntry cached = cache.get(teamName);
        if (cached != null && !isExpired(cached, matchIsToday)) {
            return applyAdjustment(base, cached.missingAttackXg, cached.missingDefensiveCount);
        }

        Optional<Integer> teamId = apiFootballClient.resolveTeamId(teamName);
        if (teamId.isEmpty()) {
            return base;
        }

        List<ApiFootballSquadMember> squad = apiFootballClient.fetchSquad(teamId.get());
        Map<String, PlayerXgContribution> contributions = understatXgProvider.getPlayerContributions(teamName);

        double missingAttackXg = 0.0;
        int missingDefensiveCount = 0;
        for (ApiFootballSquadMember member : squad) {
            if (!member.injured()) continue;

            if (member.isDefensivePosition()) {
                missingDefensiveCount++;
                continue;
            }

            Optional<PlayerXgContribution> contribution = matchPlayer(member.name(), contributions);
            if (contribution.isPresent()) {
                missingAttackXg += contribution.get().per90Xg();
            }
        }

        cache.put(teamName, new CacheEntry(missingAttackXg, missingDefensiveCount, nowSupplier.get()));
        return applyAdjustment(base, missingAttackXg, missingDefensiveCount);
    }

    /** Convenience overload for callers that don't know/care about match timing - uses the long default TTL. */
    public TeamXgRating adjust(String teamName, TeamXgRating base) {
        return adjust(teamName, base, false);
    }

    private TeamXgRating applyAdjustment(TeamXgRating base, double missingAttackXg, int missingDefensiveCount) {
        double adjustedFor = Math.max(0.1, base.avgXgFor() - missingAttackXg);
        double adjustedAgainst = Math.max(0.1, base.avgXgAgainst() + (missingDefensiveCount * DEFENSIVE_ABSENCE_PENALTY));
        return new TeamXgRating(adjustedFor, adjustedAgainst, base.matchesUsed());
    }

    /**
     * Matches an API-Football player name against Understat's contribution map.
     * Tries an exact case-insensitive match first, then falls back to matching
     * by surname (last whitespace-separated token), since the two sources
     * don't always format names identically.
     */
    private Optional<PlayerXgContribution> matchPlayer(String playerName, Map<String, PlayerXgContribution> contributions) {
        if (playerName == null || contributions.isEmpty()) return Optional.empty();

        String normalizedTarget = playerName.toLowerCase(Locale.ROOT).trim();
        for (Map.Entry<String, PlayerXgContribution> entry : contributions.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).trim().equals(normalizedTarget)) {
                return Optional.of(entry.getValue());
            }
        }

        String targetSurname = surnameOf(normalizedTarget);
        for (Map.Entry<String, PlayerXgContribution> entry : contributions.entrySet()) {
            if (surnameOf(entry.getKey().toLowerCase(Locale.ROOT).trim()).equals(targetSurname)) {
                return Optional.of(entry.getValue());
            }
        }

        return Optional.empty();
    }

    private String surnameOf(String fullName) {
        String[] parts = fullName.split("\\s+");
        return parts.length == 0 ? fullName : parts[parts.length - 1];
    }

    private boolean isExpired(CacheEntry entry, boolean matchIsToday) {
        long ttl = matchIsToday ? IMMINENT_CACHE_TTL_SECONDS : DEFAULT_CACHE_TTL_SECONDS;
        return ChronoUnit.SECONDS.between(entry.fetchedAt, nowSupplier.get()) > ttl;
    }

    private record CacheEntry(double missingAttackXg, int missingDefensiveCount, Instant fetchedAt) {
    }
}
