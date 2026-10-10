package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adjusts a team's base Understat xG rating for missing regular starters.
 *
 * Two FotMob signals:
 * 1. Confirmed starting XI (usually posted ~1 hour before kickoff): any
 *    near-ever-present player not in the XI counts as missing, whatever the
 *    reason (rotation included).
 * 2. Before that, the unavailable list (injuries/suspensions), ignoring
 *    players marked doubtful or expected back by matchday.
 *
 * The size of the effect was fitted on ~1,100 past matches (2022-24) using
 * Understat's records of who actually started, and checked on ~420 later
 * ones: losing attackers who produce a share S of the team's xG cuts its
 * expected goals by about exp(-0.245 * S). Missing defenders showed no
 * measurable effect, so they're ignored. The whole effect is small (about
 * 0.001 Brier on held-out matches), so recommendations don't use this layer
 * - it's logged to measure whether Kalshi prices react slowly to lineups.
 *
 * If FotMob data isn't available, the base rating passes through unchanged.
 */
@Service
public class LineupFormAdjustmentService {

    private static final int ENGLISH_PREMIER_LEAGUE_ID = 47;

    // Fitted effect on expected goals is exp(-0.245 * S); the rating enters
    // the goals formula raised to XgService.ATTACK_EXPONENT, so divide by it
    // to keep that same effect on goals.
    static final double MISSING_ATTACK_COEFFICIENT = 0.245 / XgService.ATTACK_EXPONENT;

    // Only near-ever-present players count: at least this share of the most
    // minutes any squad player has in the window. A looser definition (50%)
    // mostly picked up rotation and showed no measurable effect.
    private static final double REGULAR_SHARE_OF_MAX_MINUTES = 0.8;

    // FotMob expected-return text like "Mid October 2026".
    private static final Pattern EXPECTED_RETURN_PATTERN =
        Pattern.compile("(?i)\\b(early|mid|late)\\s+([a-z]+)\\s+(\\d{4})\\b");

    // Politeness toward FotMob - but on match day re-check every scan, so a
    // posted lineup is noticed within minutes rather than up to half an hour late.
    private static final long CACHE_TTL_SECONDS = 30 * 60;
    private static final long MATCH_DAY_CACHE_TTL_SECONDS = 4 * 60;

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
     * Adjusts the given base rating for missing regulars. Returns the base
     * rating unchanged if FotMob data isn't available for this team or this
     * fixture can't be found.
     */
    public TeamXgRating adjust(String teamName, TeamXgRating base, MatchContext context) {
        if (context == null) {
            return base; // no fixture to look up - nothing to adjust
        }
        double missingAttackXg = absences(teamName, context).missingAttackXg();
        double share = base.avgXgFor() > 0 ? missingAttackXg / base.avgXgFor() : 0.0;
        double adjustedFor = base.avgXgFor() * Math.exp(-MISSING_ATTACK_COEFFICIENT * share);
        return new TeamXgRating(adjustedFor, base.avgXgAgainst(), base.matchesUsed());
    }

    /** Convenience overload for callers with no fixture context. */
    public TeamXgRating adjust(String teamName, TeamXgRating base) {
        return adjust(teamName, base, null);
    }

    /** True once FotMob has this team's confirmed starting XI for the fixture. */
    public boolean hasConfirmedLineup(String teamName, MatchContext context) {
        return context != null && absences(teamName, context).confirmedLineup();
    }

    private Absences absences(String teamName, MatchContext context) {
        String cacheKey = teamName + "|" + context.opponentTeamName() + "|" + context.matchDate();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !isExpired(cached, context.matchDate())) {
            return cached.absences();
        }
        Absences absences = computeAbsences(teamName, context);
        cache.put(cacheKey, new CacheEntry(absences, nowSupplier.get()));
        return absences;
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
            ? absencesFromUnavailableList(unavailable, contributions, context.matchDate())
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

    /** Any near-ever-present player not confirmed in today's starting XI, whatever the reason. */
    private Absences absencesFromMissingRegulars(Map<String, PlayerXgContribution> contributions, List<String> confirmedStarters) {
        Absences absences = Absences.CONFIRMED_NONE;
        for (PlayerXgContribution contribution : contributions.values()) {
            if (!isRegular(contribution, contributions)) continue;
            if (isNameInList(contribution.playerName(), confirmedStarters)) continue; // started today
            absences = absences.plus(contribution);
        }
        return absences;
    }

    /** Falls back to FotMob's unavailable-player list when no confirmed lineup is posted yet. */
    private Absences absencesFromUnavailableList(List<FotMobUnavailablePlayer> unavailable,
                                                 Map<String, PlayerXgContribution> contributions,
                                                 LocalDate matchDate) {
        Absences absences = Absences.NONE;
        for (FotMobUnavailablePlayer player : unavailable) {
            if (!isExpectedToMiss(player, matchDate)) continue;

            Optional<PlayerXgContribution> contribution = matchPlayer(player.name(), contributions);
            if (contribution.isEmpty()) continue; // can't confidently attribute an impact without matching to real data
            if (!isRegular(contribution.get(), contributions)) continue;

            absences = absences.plus(contribution.get());
        }
        return absences;
    }

    private boolean isRegular(PlayerXgContribution player, Map<String, PlayerXgContribution> squad) {
        int maxMinutes = squad.values().stream().mapToInt(PlayerXgContribution::totalMinutes).max().orElse(0);
        return maxMinutes > 0 && player.totalMinutes() >= REGULAR_SHARE_OF_MAX_MINUTES * maxMinutes;
    }

    /**
     * FotMob lists players days or weeks ahead, including ones marked
     * "Doubtful" or expected back before this match - those may well play,
     * so only count players with no sign of returning by matchday.
     */
    static boolean isExpectedToMiss(FotMobUnavailablePlayer player, LocalDate matchDate) {
        String expectedReturn = player.expectedReturn() == null ? "" : player.expectedReturn();
        String type = player.type() == null ? "" : player.type();
        if (expectedReturn.toLowerCase(Locale.ROOT).contains("doubtful") || type.toLowerCase(Locale.ROOT).contains("doubtful")) {
            return false;
        }
        return earliestReturnDate(expectedReturn).map(returnDate -> returnDate.isAfter(matchDate)).orElse(true);
    }

    /** "Mid October 2026" -> 2026-10-11 (earliest day of that third of the month), if parseable. */
    static Optional<LocalDate> earliestReturnDate(String expectedReturn) {
        Matcher matcher = EXPECTED_RETURN_PATTERN.matcher(expectedReturn);
        if (!matcher.find()) return Optional.empty();
        try {
            Month month = Month.valueOf(matcher.group(2).toUpperCase(Locale.ROOT));
            int day = switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
                case "early" -> 1;
                case "mid" -> 11;
                default -> 21;
            };
            return Optional.of(LocalDate.of(Integer.parseInt(matcher.group(3)), month, day));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
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

    private boolean isExpired(CacheEntry entry, LocalDate matchDate) {
        boolean matchDay = LocalDate.ofInstant(nowSupplier.get(), ZoneOffset.UTC).equals(matchDate);
        long ttl = matchDay ? MATCH_DAY_CACHE_TTL_SECONDS : CACHE_TTL_SECONDS;
        return ChronoUnit.SECONDS.between(entry.fetchedAt(), nowSupplier.get()) > ttl;
    }

    /** Missing attackers' combined per-90 xG (defenders showed no measurable effect, so aren't counted). */
    private record Absences(double missingAttackXg, boolean confirmedLineup) {
        static final Absences NONE = new Absences(0.0, false);
        static final Absences CONFIRMED_NONE = new Absences(0.0, true);

        Absences plus(PlayerXgContribution player) {
            return player.defensivePosition() ? this : new Absences(missingAttackXg + player.per90Xg(), confirmedLineup);
        }
    }

    private record CacheEntry(Absences absences, Instant fetchedAt) {
    }
}
