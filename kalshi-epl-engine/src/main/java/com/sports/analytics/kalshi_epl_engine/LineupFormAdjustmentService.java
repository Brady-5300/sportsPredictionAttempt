package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Adjusts a team's base Understat xG rating for confirmed missing players.
 *
 * Two FotMob signals, used together:
 * 1. Unavailable list (injuries/suspensions) - populated well before kickoff.
 * 2. Confirmed starting XI (usually only posted ~1 hour before kickoff): any
 *    player who's normally a regular (per Understat minutes) but isn't in the
 *    posted XI is treated as missing too, whatever the reason (rotation
 *    included) - a more complete signal than the unavailable list alone.
 *
 * Either way, "how much does missing this player matter" is estimated from
 * their own recent per-90 xG contribution (computed from Understat shot data,
 * see UnderstatXgProvider.getPlayerContributions) for attackers/midfielders,
 * and a flat xG-against penalty for missing GK/defenders (shot data alone
 * doesn't give a clean defensive-contribution metric).
 *
 * FotMob's API has no publisher-enforced request cap (unlike the RapidAPI
 * reseller this replaced, which was hard-capped at 100/month) - so caching
 * here is just to avoid redundant calls within a scan cycle and be a
 * reasonable citizen of someone else's free API, not quota management.
 *
 * If FotMob data isn't available (team not resolvable, request failure),
 * this passes the base Understat rating through unchanged rather than
 * failing the whole evaluation - it's a refinement layer, not a requirement
 * for "live data". Recent-form weighting is handled entirely by
 * UnderstatXgProvider's recency-decayed rolling window.
 */
@Service
public class LineupFormAdjustmentService {

    private static final int ENGLISH_PREMIER_LEAGUE_ID = 47;

    // Missing defensive players don't have a clean "goals prevented" stat from
    // shot data alone, so each confirmed-out GK/DEF gets a flat xG-against bump
    // instead of a per-player-measured one.
    private static final double DEFENSIVE_ABSENCE_PENALTY = 0.12;

    // A player averaging at least this many minutes over the Understat rolling
    // window (out of up to ~540 for 6 games) counts as a "regular" whose absence
    // from a confirmed lineup is worth flagging, rather than a fringe squad player.
    private static final int REGULAR_STARTER_MINUTES_THRESHOLD = 270;

    private static final long CACHE_TTL_SECONDS = 30 * 60; // 30 minutes - politeness, not quota management

    private final FotMobClient fotMobClient;
    private final UnderstatXgProvider understatXgProvider;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    Supplier<Instant> nowSupplier = Instant::now;

    public LineupFormAdjustmentService(FotMobClient fotMobClient, UnderstatXgProvider understatXgProvider) {
        this.fotMobClient = fotMobClient;
        this.understatXgProvider = understatXgProvider;
    }

    /** Context needed to look up a specific fixture. Pass null when unknown/not applicable. */
    public record MatchContext(String opponentTeamName, boolean isHomeSide, LocalDate matchDate) {
    }

    /**
     * Adjusts the given base rating for confirmed missing players. Returns the
     * base rating unchanged if FotMob data isn't available for this team or
     * this specific fixture can't be found.
     */
    public TeamXgRating adjust(String teamName, TeamXgRating base, MatchContext context) {
        if (context == null) {
            return base; // no fixture to look up - nothing to adjust
        }

        String cacheKey = teamName + "|" + context.opponentTeamName() + "|" + context.matchDate();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !isExpired(cached)) {
            return applyAdjustment(base, cached.missingAttackXg, cached.missingDefensiveCount);
        }

        Absences absences = computeAbsences(teamName, context);
        cache.put(cacheKey, new CacheEntry(absences.missingAttackXg, absences.missingDefensiveCount, nowSupplier.get()));
        return applyAdjustment(base, absences.missingAttackXg, absences.missingDefensiveCount);
    }

    /** Convenience overload for callers with no fixture context. */
    public TeamXgRating adjust(String teamName, TeamXgRating base) {
        return adjust(teamName, base, null);
    }

    private Absences computeAbsences(String teamName, MatchContext context) {
        Optional<Integer> teamId = fotMobClient.resolveTeamId(teamName);
        if (teamId.isEmpty()) {
            return Absences.NONE;
        }

        Optional<Integer> opponentId = fotMobClient.resolveTeamId(context.opponentTeamName());
        if (opponentId.isEmpty()) {
            return Absences.NONE;
        }

        Optional<Integer> matchId = findMatchId(teamId.get(), context.matchDate());
        if (matchId.isEmpty()) {
            return Absences.NONE;
        }

        FotMobMatchLineups lineups = fotMobClient.fetchMatchLineups(matchId.get());
        List<String> starters = context.isHomeSide() ? lineups.homeStarters() : lineups.awayStarters();
        List<FotMobUnavailablePlayer> unavailable = context.isHomeSide() ? lineups.homeUnavailable() : lineups.awayUnavailable();

        Map<String, PlayerXgContribution> contributions = understatXgProvider.getPlayerContributions(teamName);

        return starters.isEmpty()
            ? absencesFromUnavailableList(unavailable, contributions)
            : absencesFromMissingRegulars(contributions, starters);
    }

    private Optional<Integer> findMatchId(int teamId, LocalDate matchDate) {
        List<FotMobFixture> fixtures = fotMobClient.fetchFixtures(teamId);
        for (FotMobFixture fixture : fixtures) {
            if (fixture.leagueId() == ENGLISH_PREMIER_LEAGUE_ID && fixture.matchDate().isEqual(matchDate)) {
                return Optional.of(fixture.matchId());
            }
        }
        return Optional.empty();
    }

    /** Any normally-regular player not confirmed in today's starting XI, whatever the reason. */
    private Absences absencesFromMissingRegulars(Map<String, PlayerXgContribution> contributions, List<String> confirmedStarters) {
        double missingAttackXg = 0.0;
        int missingDefensiveCount = 0;

        for (PlayerXgContribution contribution : contributions.values()) {
            if (contribution.totalMinutes() < REGULAR_STARTER_MINUTES_THRESHOLD) continue; // not a regular anyway
            if (isNameInList(contribution.playerName(), confirmedStarters)) continue; // started today

            if (contribution.defensivePosition()) {
                missingDefensiveCount++;
            } else {
                missingAttackXg += contribution.per90Xg();
            }
        }

        return new Absences(missingAttackXg, missingDefensiveCount);
    }

    /** Falls back to FotMob's unavailable-player list when no confirmed lineup is posted yet. */
    private Absences absencesFromUnavailableList(List<FotMobUnavailablePlayer> unavailable, Map<String, PlayerXgContribution> contributions) {
        double missingAttackXg = 0.0;
        int missingDefensiveCount = 0;

        for (FotMobUnavailablePlayer player : unavailable) {
            Optional<PlayerXgContribution> contribution = matchPlayer(player.name(), contributions);
            if (contribution.isEmpty()) continue; // can't confidently attribute an impact without matching to real data

            if (contribution.get().defensivePosition()) {
                missingDefensiveCount++;
            } else {
                missingAttackXg += contribution.get().per90Xg();
            }
        }

        return new Absences(missingAttackXg, missingDefensiveCount);
    }

    private TeamXgRating applyAdjustment(TeamXgRating base, double missingAttackXg, int missingDefensiveCount) {
        double adjustedFor = Math.max(0.1, base.avgXgFor() - missingAttackXg);
        double adjustedAgainst = Math.max(0.1, base.avgXgAgainst() + (missingDefensiveCount * DEFENSIVE_ABSENCE_PENALTY));
        return new TeamXgRating(adjustedFor, adjustedAgainst, base.matchesUsed());
    }

    private boolean isNameInList(String playerName, List<String> names) {
        String normalizedTarget = playerName.toLowerCase(Locale.ROOT).trim();
        String targetSurname = surnameOf(normalizedTarget);

        for (String name : names) {
            String normalized = name.toLowerCase(Locale.ROOT).trim();
            if (normalized.equals(normalizedTarget) || surnameOf(normalized).equals(targetSurname)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches a FotMob player name against Understat's contribution map.
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

    private boolean isExpired(CacheEntry entry) {
        return ChronoUnit.SECONDS.between(entry.fetchedAt, nowSupplier.get()) > CACHE_TTL_SECONDS;
    }

    private record Absences(double missingAttackXg, int missingDefensiveCount) {
        static final Absences NONE = new Absences(0.0, 0);
    }

    private record CacheEntry(double missingAttackXg, int missingDefensiveCount, Instant fetchedAt) {
    }
}
