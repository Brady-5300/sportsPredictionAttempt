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

    private final UnderstatScraperService scraper = mock(UnderstatScraperService.class);
    private final ShotXgCalculator calculator = mock(ShotXgCalculator.class);
    private final TeamNameResolver resolver = new TeamNameResolver();
    private final UnderstatXgProvider provider = new UnderstatXgProvider(scraper, calculator, resolver);

    private UnderstatTeamMatch completedMatch(String id, String side) {
        UnderstatTeamMatch match = new UnderstatTeamMatch();
        match.setId(id);
        match.setIsResult(true);
        match.setSide(side);
        match.setDatetime("2025-01-0" + id.charAt(id.length() - 1) + " 15:00:00");
        return match;
    }

    private UnderstatShot shot(String player) {
        UnderstatShot s = new UnderstatShot();
        s.setPlayer(player);
        return s; // xG value is stubbed via the mocked calculator, not read from the shot
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

    @Test
    void returnsEmptyForTeamWithNoUnderstatSlug() {
        Optional<TeamXgRating> rating = provider.getRating("Some Newly Promoted Club");

        assertTrue(rating.isEmpty());
        verifyNoInteractions(scraper);
    }

    @Test
    void aggregatesRecencyWeightedXgForAndAgainstAcrossCompletedMatches() {
        // match "1" is dated 2025-01-01, match "2" is dated 2025-01-02, so "2" is more
        // recent and should get the full weight while "1" is discounted by the decay factor.
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(
            completedMatch("1", "h"),
            completedMatch("2", "a")
        ));

        UnderstatShot ourShot1 = shot("Bukayo Saka");
        UnderstatShot theirShot1 = shot("Opponent1");
        stubMatch("1", List.of(ourShot1), List.of(theirShot1), List.of(), List.of());

        UnderstatShot ourShot2 = shot("Bukayo Saka");
        UnderstatShot theirShot2 = shot("Opponent2");
        stubMatch("2", List.of(theirShot2), List.of(ourShot2), List.of(), List.of());

        // match 1 (older, weight 0.75): we were home -> our shots = ourShot1 (xG 2.0), their shots = theirShot1 (xG 0.5)
        // match 2 (more recent, weight 1.0): we were away -> our shots = ourShot2 (xG 1.0), their shots = theirShot2 (xG 1.5)
        when(calculator.calculateXg(ourShot1)).thenReturn(2.0);
        when(calculator.calculateXg(theirShot1)).thenReturn(0.5);
        when(calculator.calculateXg(ourShot2)).thenReturn(1.0);
        when(calculator.calculateXg(theirShot2)).thenReturn(1.5);

        Optional<TeamXgRating> rating = provider.getRating("Arsenal");

        assertTrue(rating.isPresent());
        // weightedFor = 1.0*1.0 (match 2) + 0.75*2.0 (match 1) = 2.5; weightSum = 1.75
        assertEquals(2.5 / 1.75, rating.get().avgXgFor(), 0.0001);
        // weightedAgainst = 1.0*1.5 (match 2) + 0.75*0.5 (match 1) = 1.875; weightSum = 1.75
        assertEquals(1.875 / 1.75, rating.get().avgXgAgainst(), 0.0001);
        assertEquals(2, rating.get().matchesUsed());
    }

    @Test
    void mostRecentMatchIsWeightedMoreThanAnOlderOne() {
        // Two providers with identical raw match data except which match is "more recent" -
        // the one whose big attacking match was most recent should end up with a
        // higher weighted average than the one whose big match was further back.
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(
            completedMatch("1", "h"), // 2025-01-01, older
            completedMatch("2", "h")  // 2025-01-02, more recent
        ));

        UnderstatShot bigMatchShot = shot("Player A");
        UnderstatShot smallMatchShot = shot("Player A");
        when(calculator.calculateXg(bigMatchShot)).thenReturn(3.0);
        when(calculator.calculateXg(smallMatchShot)).thenReturn(0.5);

        // Big attacking match is the OLDER one (match "1"), small one is more recent (match "2").
        stubMatch("1", List.of(bigMatchShot), List.of(), List.of(), List.of());
        stubMatch("2", List.of(smallMatchShot), List.of(), List.of(), List.of());

        double bigMatchOlderAvg = provider.getRating("Arsenal").get().avgXgFor();

        // Now flip which match is more recent by reusing a fresh provider (no cache reuse).
        UnderstatXgProvider provider2 = new UnderstatXgProvider(scraper, calculator, resolver);
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

        verify(scraper, times(1)).fetchTeamMatches(eq("Arsenal"), anyInt());
    }

    @Test
    void refetchesAfterCacheExpires() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        provider.nowSupplier = () -> start;

        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(completedMatch("1", "h")));
        stubMatch("1", List.of(), List.of(), List.of(), List.of());

        provider.getRating("Arsenal");
        provider.nowSupplier = () -> start.plusSeconds(7 * 60 * 60); // 7h later, past the 6h TTL
        provider.getRating("Arsenal");

        verify(scraper, times(2)).fetchTeamMatches(eq("Arsenal"), anyInt());
    }

    @Test
    void treatsEmptyShotListsAsZeroXgNotAsMissingData() {
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(completedMatch("1", "h")));
        stubMatch("1", List.of(), List.of(), List.of(), List.of());

        Optional<TeamXgRating> rating = provider.getRating("Arsenal");

        assertTrue(rating.isPresent());
        assertEquals(0.0, rating.get().avgXgFor(), 0.0001);
        assertFalse(rating.get().matchesUsed() == 0);
    }

    @Test
    void aggregatesPlayerContributionsAcrossMatches() {
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(
            completedMatch("1", "h"),
            completedMatch("2", "a")
        ));

        UnderstatShot sakaShot1 = shot("Bukayo Saka");
        UnderstatShot sakaShot2 = shot("Bukayo Saka");
        when(calculator.calculateXg(sakaShot1)).thenReturn(0.4);
        when(calculator.calculateXg(sakaShot2)).thenReturn(0.6);

        stubMatch("1", List.of(sakaShot1), List.of(),
            List.of(rosterEntry("Bukayo Saka", 90, "FWR"), rosterEntry("William Saliba", 90, "DC")),
            List.of());
        stubMatch("2", List.of(), List.of(sakaShot2),
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
    void returnsEmptyContributionsWhenNoLiveDataAvailable() {
        Map<String, PlayerXgContribution> contributions = provider.getPlayerContributions("Some Newly Promoted Club");
        assertTrue(contributions.isEmpty());
    }

    // === getRatingAsOf (point-in-time, for backtesting) ===

    @Test
    void ratingAsOfOnlyUsesMatchesStrictlyBeforeTheGivenDate() {
        // Three matches: Jan 1, Jan 3, Jan 5. Backtesting "as of" Jan 5 should only
        // see Jan 1 and Jan 3 - NOT the Jan 5 match itself (that would be lookahead).
        UnderstatTeamMatch m1 = completedMatchOn("1", "h", "2025-01-01");
        UnderstatTeamMatch m2 = completedMatchOn("2", "h", "2025-01-03");
        UnderstatTeamMatch m3 = completedMatchOn("3", "h", "2025-01-05");
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(m1, m2, m3));

        UnderstatShot shot1 = shot("Player A");
        UnderstatShot shot2 = shot("Player A");
        UnderstatShot shot3 = shot("Player A");
        when(calculator.calculateXg(shot1)).thenReturn(1.0);
        when(calculator.calculateXg(shot2)).thenReturn(2.0);
        when(calculator.calculateXg(shot3)).thenReturn(100.0); // would massively skew the rating if leaked in

        stubMatch("1", List.of(shot1), List.of(), List.of(), List.of());
        stubMatch("2", List.of(shot2), List.of(), List.of(), List.of());
        stubMatch("3", List.of(shot3), List.of(), List.of(), List.of());

        Optional<TeamXgRating> asOfJan5 = provider.getRatingAsOf("Arsenal", java.time.LocalDate.of(2025, 1, 5));

        assertTrue(asOfJan5.isPresent());
        assertEquals(2, asOfJan5.get().matchesUsed());
        assertTrue(asOfJan5.get().avgXgFor() < 10.0, "the Jan 5 match's huge xG must not leak into a rating computed as of Jan 5");
    }

    @Test
    void ratingAsOfReturnsEmptyWhenNoMatchesBeforeThatDate() {
        UnderstatTeamMatch futureMatch = completedMatchOn("1", "h", "2025-06-01");
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(futureMatch));

        Optional<TeamXgRating> asOfEarlyDate = provider.getRatingAsOf("Arsenal", java.time.LocalDate.of(2025, 1, 1));

        assertTrue(asOfEarlyDate.isEmpty());
    }

    @Test
    void ratingAsOfReturnsEmptyForUnmappedTeam() {
        assertTrue(provider.getRatingAsOf("Some Newly Promoted Club", java.time.LocalDate.of(2025, 1, 1)).isEmpty());
        verifyNoInteractions(scraper);
    }

    private UnderstatTeamMatch completedMatchOn(String id, String side, String isoDate) {
        UnderstatTeamMatch match = new UnderstatTeamMatch();
        match.setId(id);
        match.setIsResult(true);
        match.setSide(side);
        match.setDatetime(isoDate + " 15:00:00");
        return match;
    }
}
