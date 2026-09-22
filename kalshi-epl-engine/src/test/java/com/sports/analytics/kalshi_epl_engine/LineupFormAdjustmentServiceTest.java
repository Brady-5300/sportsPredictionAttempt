package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LineupFormAdjustmentServiceTest {

    private final ApiFootballClient apiFootballClient = mock(ApiFootballClient.class);
    private final UnderstatXgProvider understatXgProvider = mock(UnderstatXgProvider.class);
    private final LineupFormAdjustmentService service =
        new LineupFormAdjustmentService(apiFootballClient, understatXgProvider);

    private static final TeamXgRating BASE = new TeamXgRating(2.0, 1.0, 6);

    @Test
    void passesRatingThroughUnchangedWhenTeamCannotBeResolved() {
        when(apiFootballClient.resolveTeamId("Arsenal")).thenReturn(Optional.empty());

        TeamXgRating result = service.adjust("Arsenal", BASE);

        assertEquals(BASE, result);
    }

    @Test
    void subtractsMissingAttackingPlayersMeasuredContribution() {
        when(apiFootballClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(apiFootballClient.fetchSquad(9825)).thenReturn(List.of(
            new ApiFootballSquadMember("Bukayo Saka", "RW", true, "2 weeks")
        ));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of(
            "Bukayo Saka", new PlayerXgContribution("Bukayo Saka", false, 3.6, 360) // 0.9 xG per 90
        ));

        TeamXgRating result = service.adjust("Arsenal", BASE);

        assertEquals(BASE.avgXgFor() - 0.9, result.avgXgFor(), 0.0001);
        assertEquals(BASE.avgXgAgainst(), result.avgXgAgainst(), 0.0001);
    }

    @Test
    void appliesFlatPenaltyForMissingDefensivePlayersWithoutNeedingUnderstatMatch() {
        when(apiFootballClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(apiFootballClient.fetchSquad(9825)).thenReturn(List.of(
            new ApiFootballSquadMember("William Saliba", "CB", true, "Mid October")
        ));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of());

        TeamXgRating result = service.adjust("Arsenal", BASE);

        assertEquals(BASE.avgXgAgainst() + 0.12, result.avgXgAgainst(), 0.0001);
        assertEquals(BASE.avgXgFor(), result.avgXgFor(), 0.0001);
    }

    @Test
    void ignoresHealthySquadMembers() {
        when(apiFootballClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(apiFootballClient.fetchSquad(9825)).thenReturn(List.of(
            new ApiFootballSquadMember("Bukayo Saka", "RW", false, null),
            new ApiFootballSquadMember("William Saliba", "CB", false, null)
        ));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of(
            "Bukayo Saka", new PlayerXgContribution("Bukayo Saka", false, 3.6, 360)
        ));

        TeamXgRating result = service.adjust("Arsenal", BASE);

        assertEquals(BASE, result);
    }

    @Test
    void matchesInjuredPlayerBySurnameWhenExactNameDiffers() {
        when(apiFootballClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(apiFootballClient.fetchSquad(9825)).thenReturn(List.of(
            new ApiFootballSquadMember("B. Saka", "RW", true, "2 weeks")
        ));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of(
            "Bukayo Saka", new PlayerXgContribution("Bukayo Saka", false, 0.9, 90)
        ));

        TeamXgRating result = service.adjust("Arsenal", BASE);

        assertEquals(BASE.avgXgFor() - 0.9, result.avgXgFor(), 0.0001);
    }

    @Test
    void ignoresInjuredPlayerThatCannotBeMatchedToAnyUnderstatData() {
        when(apiFootballClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(apiFootballClient.fetchSquad(9825)).thenReturn(List.of(
            new ApiFootballSquadMember("Completely Unknown Player", "CM", true, "Illness")
        ));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of(
            "Bukayo Saka", new PlayerXgContribution("Bukayo Saka", false, 0.9, 90)
        ));

        TeamXgRating result = service.adjust("Arsenal", BASE);

        assertEquals(BASE, result); // no confident attribution, no adjustment applied
    }

    @Test
    void cachesAndDoesNotRefetchWithinTheLongDefaultTtlWhenMatchIsNotToday() {
        when(apiFootballClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(apiFootballClient.fetchSquad(9825)).thenReturn(List.of());
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of());

        service.adjust("Arsenal", BASE, false);
        service.adjust("Arsenal", BASE, false);

        verify(apiFootballClient, times(1)).resolveTeamId("Arsenal");
    }

    @Test
    void refreshesSoonerWhenMatchIsToday() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        service.nowSupplier = () -> start;

        when(apiFootballClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(apiFootballClient.fetchSquad(9825)).thenReturn(List.of());
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of());

        service.adjust("Arsenal", BASE, true);
        // 45 minutes later: still within the long default TTL, but past the imminent (30 min) TTL.
        service.nowSupplier = () -> start.plusSeconds(45 * 60);
        service.adjust("Arsenal", BASE, true);

        verify(apiFootballClient, times(2)).resolveTeamId("Arsenal");
    }

    @Test
    void doesNotRefreshSoonerJustBecauseTodayFlagWasSetOnceIfLaterCallsSayNotToday() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        service.nowSupplier = () -> start;

        when(apiFootballClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(apiFootballClient.fetchSquad(9825)).thenReturn(List.of());
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of());

        service.adjust("Arsenal", BASE, false);
        // 45 minutes later, still well within the long default TTL if not treated as imminent.
        service.nowSupplier = () -> start.plusSeconds(45 * 60);
        service.adjust("Arsenal", BASE, false);

        verify(apiFootballClient, times(1)).resolveTeamId("Arsenal");
    }
}
