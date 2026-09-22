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

    private UnderstatShot shot(double xg) {
        UnderstatShot s = new UnderstatShot();
        return s; // real xG value is stubbed via the mocked calculator, not read from the shot
    }

    @Test
    void returnsEmptyForTeamWithNoUnderstatSlug() {
        Optional<TeamXgRating> rating = provider.getRating("Some Newly Promoted Club");

        assertTrue(rating.isEmpty());
        org.mockito.Mockito.verifyNoInteractions(scraper);
    }

    @Test
    void aggregatesAverageXgForAndAgainstAcrossCompletedMatches() {
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(
            completedMatch("1", "h"),
            completedMatch("2", "a")
        ));

        UnderstatShot ourShot1 = shot(0);
        UnderstatShot theirShot1 = shot(0);
        when(scraper.fetchMatchShots("1")).thenReturn(Map.of(
            "h", List.of(ourShot1),
            "a", List.of(theirShot1)
        ));

        UnderstatShot ourShot2 = shot(0);
        UnderstatShot theirShot2 = shot(0);
        when(scraper.fetchMatchShots("2")).thenReturn(Map.of(
            "h", List.of(theirShot2),
            "a", List.of(ourShot2)
        ));

        // match 1: we were home -> our shots = ourShot1 (xG 2.0), their shots = theirShot1 (xG 0.5)
        // match 2: we were away -> our shots = ourShot2 (xG 1.0), their shots = theirShot2 (xG 1.5)
        when(calculator.calculateXg(ourShot1)).thenReturn(2.0);
        when(calculator.calculateXg(theirShot1)).thenReturn(0.5);
        when(calculator.calculateXg(ourShot2)).thenReturn(1.0);
        when(calculator.calculateXg(theirShot2)).thenReturn(1.5);

        Optional<TeamXgRating> rating = provider.getRating("Arsenal");

        assertTrue(rating.isPresent());
        assertEquals(1.5, rating.get().avgXgFor(), 0.0001); // (2.0 + 1.0) / 2
        assertEquals(1.0, rating.get().avgXgAgainst(), 0.0001); // (0.5 + 1.5) / 2
        assertEquals(2, rating.get().matchesUsed());
    }

    @Test
    void ignoresUnplayedFixturesWhenAggregating() {
        UnderstatTeamMatch upcoming = new UnderstatTeamMatch();
        upcoming.setId("99");
        upcoming.setIsResult(false);

        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(upcoming));

        Optional<TeamXgRating> rating = provider.getRating("Arsenal");

        assertTrue(rating.isEmpty());
        org.mockito.Mockito.verify(scraper, times(0)).fetchMatchShots(anyString());
    }

    @Test
    void cachesRatingAndDoesNotRefetchWithinTtl() {
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(completedMatch("1", "h")));
        when(scraper.fetchMatchShots("1")).thenReturn(Map.of("h", List.of(), "a", List.of()));

        provider.getRating("Arsenal");
        provider.getRating("Arsenal");

        verify(scraper, times(1)).fetchTeamMatches(eq("Arsenal"), anyInt());
    }

    @Test
    void refetchesAfterCacheExpires() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        provider.nowSupplier = () -> start;

        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(completedMatch("1", "h")));
        when(scraper.fetchMatchShots("1")).thenReturn(Map.of("h", List.of(), "a", List.of()));

        provider.getRating("Arsenal");
        provider.nowSupplier = () -> start.plusSeconds(7 * 60 * 60); // 7h later, past the 6h TTL
        provider.getRating("Arsenal");

        verify(scraper, times(2)).fetchTeamMatches(eq("Arsenal"), anyInt());
    }

    @Test
    void treatsEmptyShotListsAsZeroXgNotAsMissingData() {
        when(scraper.fetchTeamMatches(eq("Arsenal"), anyInt())).thenReturn(List.of(completedMatch("1", "h")));
        when(scraper.fetchMatchShots("1")).thenReturn(Map.of("h", List.of(), "a", List.of()));

        Optional<TeamXgRating> rating = provider.getRating("Arsenal");

        assertTrue(rating.isPresent());
        assertEquals(0.0, rating.get().avgXgFor(), 0.0001);
        assertFalse(rating.get().matchesUsed() == 0);
    }
}
