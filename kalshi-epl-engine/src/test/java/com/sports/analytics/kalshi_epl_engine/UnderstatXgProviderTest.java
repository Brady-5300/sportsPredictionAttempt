package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UnderstatXgProviderTest {

    // "Today" is mid 2024/25 season, so current season = 2024, previous = 2023.
    private static final Instant NOW = Instant.parse("2025-02-01T00:00:00Z");
    private static final int CURRENT_SEASON = 2024;
    private static final int PREVIOUS_SEASON = 2023;

    private static final double DECAY = 0.96;
    private static final double PRIOR_MATCHES = 3.0;
    private static final double LEAGUE_AVG = 1.40;
    private static final double PROMOTED_FOR = 1.14;
    private static final double PROMOTED_AGAINST = 1.87;

    private final UnderstatScraperService scraper = mock(UnderstatScraperService.class);
    private final ShotXgCalculator calculator = mock(ShotXgCalculator.class);
    private final TeamNameResolver resolver = new TeamNameResolver();
    private final UnderstatXgProvider provider = newProvider();

    private UnderstatXgProvider newProvider() {
        UnderstatXgProvider p = new UnderstatXgProvider(scraper, calculator, resolver);
        p.nowSupplier = () -> NOW;
        return p;
    }

    private UnderstatTeamMatch completedMatch(String id, String side) {
        return completedMatchOn(id, side, "2025-01-0" + id.charAt(id.length() - 1));
    }

    private UnderstatTeamMatch completedMatchOn(String id, String side, String isoDate) {
        UnderstatTeamMatch match = new UnderstatTeamMatch();
        match.setId(id);
        match.setIsResult(true);
        match.setSide(side);
        match.setDatetime(isoDate + " 15:00:00");
        return match;
    }

    private UnderstatShot shot(String player) {
        UnderstatShot s = new UnderstatShot();
        s.setPlayer(player);
        return s; // xG value is stubbed via the mocked calculator, not read from the shot
    }

    private UnderstatShot shotWithXg(String player, double xg) {
        UnderstatShot s = shot(player);
        when(calculator.calculateXg(s)).thenReturn(xg);
        return s;
    }

    private UnderstatPlayerMatchStat rosterEntry(String player, int minutes, String position) {
        UnderstatPlayerMatchStat stat = new UnderstatPlayerMatchStat();
        stat.setPlayer(player);
        stat.setTime(String.valueOf(minutes));
        stat.setPosition(position);
        return stat;
    }

    private void stubMatch(String matchId, List<UnderstatShot> homeShots, List<UnderstatShot> awayShots,
                            List<UnderstatPlayerMatchStat> homeRoster, List<UnderstatPlayerMatchStat> awayRoster) {
        when(scraper.fetchMatchDetails(matchId)).thenReturn(new UnderstatMatchDetails(
            Map.of("h", homeShots, "a", awayShots),
            Map.of("h", homeRoster, "a", awayRoster)
        ));
    }

    private static double shrunk(double weightedSum, double weightSum, double prior) {
        return (weightedSum + PRIOR_MATCHES * prior) / (weightSum + PRIOR_MATCHES);
    }

    @Test
    void returnsEmptyForTeamWithNoUnderstatSlug() {
        Optional<TeamXgRating> rating = provider.getRating("Some Newly Promoted Club");

        assertTrue(rating.isEmpty());
        verifyNoInteractions(scraper);
    }

    @Test
    void combinesThisSeasonAndLastSeasonWithRecencyDecayAndShrinksTowardLeagueAverage() {
        // Current season: match "2" (Jan 2, most recent) and "1" (Jan 1). Previous season: "p1".
        when(scraper.fetchTeamMatches("Arsenal", CURRENT_SEASON)).thenReturn(List.of(
            completedMatch("1", "h"),
            completedMatch("2", "a")
        ));
        when(scraper.fetchTeamMatches("Arsenal", PREVIOUS_SEASON)).thenReturn(List.of(
            completedMatchOn("p1", "h", "2024-03-01")
        ));

        stubMatch("1", List.of(shotWithXg("A", 2.0)), List.of(shotWithXg("B", 0.5)), List.of(), List.of());
        stubMatch("2", List.of(shotWithXg("C", 1.5)), List.of(shotWithXg("A", 1.0)), List.of(), List.of());
        stubMatch("p1", List.of(shotWithXg("A", 1.2)), List.of(shotWithXg("D", 0.8)), List.of(), List.of());

        TeamXgRating rating = provider.getRating("Arsenal").orElseThrow();

        // weights: match 2 -> 1.0, match 1 -> 0.96, p1 -> 0.96^2
        double w0 = 1.0, w1 = DECAY, w2 = DECAY * DECAY;
        double weightSum = w0 + w1 + w2;
        assertEquals(shrunk(w0 * 1.0 + w1 * 2.0 + w2 * 1.2, weightSum, LEAGUE_AVG), rating.avgXgFor(), 1e-9);
        assertEquals(shrunk(w0 * 1.5 + w1 * 0.5 + w2 * 0.8, weightSum, LEAGUE_AVG), rating.avgXgAgainst(), 1e-9);
        assertEquals(3, rating.matchesUsed());
    }

    @Test
    void promotedTeamWithNoPreviousSeasonDataIsShrunkTowardPromotedPrior() {
        when(scraper.fetchTeamMatches("Arsenal", CURRENT_SEASON)).thenReturn(List.of(completedMatch("1", "h")));
        stubMatch("1", List.of(shotWithXg("A", 2.0)), List.of(shotWithXg("B", 0.5)), List.of(), List.of());

        TeamXgRating rating = provider.getRating("Arsenal").orElseThrow();

        assertEquals(shrunk(2.0, 1.0, PROMOTED_FOR), rating.avgXgFor(), 1e-9);
        assertEquals(shrunk(0.5, 1.0, PROMOTED_AGAINST), rating.avgXgAgainst(), 1e-9);
    }

    @Test
    void previousSeasonRequestAnsweredWithThisSeasonsDataIsNotDoubleCounted() {
        // Understat answers a request for a season a team wasn't in the league
        // with a different season's fixtures - here, the same current-season list.
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(
            completedMatch("1", "h"),
            completedMatch("2", "h")
        ));
        stubMatch("1", List.of(), List.of(), List.of(), List.of());
        stubMatch("2", List.of(), List.of(), List.of(), List.of());

        TeamXgRating rating = provider.getRating("Arsenal").orElseThrow();

        assertEquals(2, rating.matchesUsed());
        assertEquals(shrunk(0.0, 1.0 + DECAY, PROMOTED_FOR), rating.avgXgFor(), 1e-9);
    }

    @Test
    void mostRecentMatchIsWeightedMoreThanAnOlderOne() {
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(
            completedMatch("1", "h"), // 2025-01-01, older
            completedMatch("2", "h")  // 2025-01-02, more recent
        ));

        UnderstatShot bigMatchShot = shotWithXg("Player A", 3.0);
        UnderstatShot smallMatchShot = shotWithXg("Player A", 0.5);

        stubMatch("1", List.of(bigMatchShot), List.of(), List.of(), List.of());
        stubMatch("2", List.of(smallMatchShot), List.of(), List.of(), List.of());
        double bigMatchOlderAvg = provider.getRating("Arsenal").get().avgXgFor();

        UnderstatXgProvider provider2 = newProvider();
        stubMatch("1", List.of(smallMatchShot), List.of(), List.of(), List.of());
        stubMatch("2", List.of(bigMatchShot), List.of(), List.of(), List.of());
        double bigMatchNewerAvg = provider2.getRating("Arsenal").get().avgXgFor();

        assertTrue(bigMatchNewerAvg > bigMatchOlderAvg,
            "weighting the big attacking match as most-recent (" + bigMatchNewerAvg
                + ") should score higher than weighting it as oldest (" + bigMatchOlderAvg + ")");
    }

    @Test
    void ignoresUnplayedFixturesWhenAggregating() {
        UnderstatTeamMatch upcoming = new UnderstatTeamMatch();
        upcoming.setId("99");
        upcoming.setIsResult(false);

        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(upcoming));

        Optional<TeamXgRating> rating = provider.getRating("Arsenal");

        assertTrue(rating.isEmpty());
        verify(scraper, times(0)).fetchMatchDetails(anyString());
    }

    @Test
    void cachesRatingAndDoesNotRefetchWithinTtl() {
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(completedMatch("1", "h")));
        stubMatch("1", List.of(), List.of(), List.of(), List.of());

        provider.getRating("Arsenal");
        provider.getRating("Arsenal");

        // One fetch each for this season and last season, then served from cache.
        verify(scraper, times(2)).fetchTeamMatches(eq("Arsenal"), anyInt());
    }

    @Test
    void refetchesAfterCacheExpires() {
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(completedMatch("1", "h")));
        stubMatch("1", List.of(), List.of(), List.of(), List.of());

        provider.getRating("Arsenal");
        provider.nowSupplier = () -> NOW.plusSeconds(7 * 60 * 60); // 7h later, past the 6h TTL
        provider.getRating("Arsenal");

        verify(scraper, times(4)).fetchTeamMatches(eq("Arsenal"), anyInt());
    }

    @Test
    void emptyShotListsCountAsARealZeroXgMatchNotMissingData() {
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(completedMatch("1", "h")));
        stubMatch("1", List.of(), List.of(), List.of(), List.of());

        TeamXgRating rating = provider.getRating("Arsenal").orElseThrow();

        assertEquals(1, rating.matchesUsed());
        assertEquals(shrunk(0.0, 1.0, PROMOTED_FOR), rating.avgXgFor(), 1e-9);
    }

    @Test
    void aggregatesPlayerContributionsAcrossMatches() {
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(
            completedMatch("1", "h"),
            completedMatch("2", "a")
        ));

        stubMatch("1", List.of(shotWithXg("Bukayo Saka", 0.4)), List.of(),
            List.of(rosterEntry("Bukayo Saka", 90, "FWR"), rosterEntry("William Saliba", 90, "DC")),
            List.of());
        stubMatch("2", List.of(), List.of(shotWithXg("Bukayo Saka", 0.6)),
            List.of(),
            List.of(rosterEntry("Bukayo Saka", 45, "FWR"), rosterEntry("William Saliba", 90, "DC")));

        Map<String, PlayerXgContribution> contributions = provider.getPlayerContributions("Arsenal");

        PlayerXgContribution saka = contributions.get("Bukayo Saka");
        assertEquals(135, saka.totalMinutes());
        assertEquals(1.0, saka.totalXg(), 0.0001);
        assertEquals((1.0 / 135) * 90, saka.per90Xg(), 0.0001);
        assertFalse(saka.defensivePosition());

        PlayerXgContribution saliba = contributions.get("William Saliba");
        assertEquals(180, saliba.totalMinutes());
        assertEquals(0.0, saliba.totalXg(), 0.0001); // no shots, but still has minutes
        assertTrue(saliba.defensivePosition());
    }

    @Test
    void playerContributionsIgnoreLastSeasonsMatches() {
        // Last season's players may have left the club - counting them would make
        // lineup-absence detection flag departed players as "missing regulars".
        when(scraper.fetchTeamMatches("Arsenal", CURRENT_SEASON)).thenReturn(List.of(completedMatch("1", "h")));
        when(scraper.fetchTeamMatches("Arsenal", PREVIOUS_SEASON)).thenReturn(List.of(completedMatchOn("p1", "h", "2024-03-01")));
        stubMatch("1", List.of(), List.of(), List.of(rosterEntry("Current Player", 90, "FWR")), List.of());
        stubMatch("p1", List.of(shotWithXg("Departed Player", 0.9)), List.of(),
            List.of(rosterEntry("Departed Player", 90, "FWR")), List.of());

        Map<String, PlayerXgContribution> contributions = provider.getPlayerContributions("Arsenal");

        assertTrue(contributions.containsKey("Current Player"));
        assertFalse(contributions.containsKey("Departed Player"));
    }

    @Test
    void returnsEmptyContributionsWhenNoLiveDataAvailable() {
        Map<String, PlayerXgContribution> contributions = provider.getPlayerContributions("Some Newly Promoted Club");
        assertTrue(contributions.isEmpty());
    }

    // === getRatingAsOf (point-in-time, for backtesting) ===

    @Test
    void ratingAsOfOnlyUsesMatchesStrictlyBeforeTheGivenDate() {
        // Three matches: Jan 1, Jan 3, Jan 5. Backtesting "as of" Jan 5 should only
        // see Jan 1 and Jan 3 - NOT the Jan 5 match itself (that would be lookahead).
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(
            completedMatchOn("1", "h", "2025-01-01"),
            completedMatchOn("2", "h", "2025-01-03"),
            completedMatchOn("3", "h", "2025-01-05")
        ));

        stubMatch("1", List.of(shotWithXg("Player A", 1.0)), List.of(), List.of(), List.of());
        stubMatch("2", List.of(shotWithXg("Player A", 2.0)), List.of(), List.of(), List.of());
        stubMatch("3", List.of(shotWithXg("Player A", 100.0)), List.of(), List.of(), List.of()); // would skew the rating if leaked in

        Optional<TeamXgRating> asOfJan5 = provider.getRatingAsOf("Arsenal", java.time.LocalDate.of(2025, 1, 5));

        assertTrue(asOfJan5.isPresent());
        assertEquals(2, asOfJan5.get().matchesUsed());
        assertTrue(asOfJan5.get().avgXgFor() < 10.0, "the Jan 5 match's huge xG must not leak into a rating computed as of Jan 5");
    }

    @Test
    void ratingAsOfIncludesTheSeasonBeforeThatDate() {
        when(scraper.fetchTeamMatches("Arsenal", 2024)).thenReturn(List.of(completedMatchOn("1", "h", "2024-08-20")));
        when(scraper.fetchTeamMatches("Arsenal", 2023)).thenReturn(List.of(completedMatchOn("p1", "h", "2024-05-10")));
        stubMatch("1", List.of(), List.of(), List.of(), List.of());
        stubMatch("p1", List.of(), List.of(), List.of(), List.of());

        TeamXgRating rating = provider.getRatingAsOf("Arsenal", java.time.LocalDate.of(2024, 9, 1)).orElseThrow();

        assertEquals(2, rating.matchesUsed());
    }

    @Test
    void ratingAsOfReturnsEmptyWhenNoMatchesBeforeThatDate() {
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(completedMatchOn("1", "h", "2025-06-01")));

        Optional<TeamXgRating> asOfEarlyDate = provider.getRatingAsOf("Arsenal", java.time.LocalDate.of(2025, 1, 1));

        assertTrue(asOfEarlyDate.isEmpty());
    }

    @Test
    void ratingAsOfReturnsEmptyForUnmappedTeam() {
        assertTrue(provider.getRatingAsOf("Some Newly Promoted Club", java.time.LocalDate.of(2025, 1, 1)).isEmpty());
        verifyNoInteractions(scraper);
    }
}
