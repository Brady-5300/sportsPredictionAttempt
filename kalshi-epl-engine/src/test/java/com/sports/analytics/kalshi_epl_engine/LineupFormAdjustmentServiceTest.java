package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LineupFormAdjustmentServiceTest {

    private final FotMobClient fotMobClient = mock(FotMobClient.class);
    private final UnderstatXgProvider understatXgProvider = mock(UnderstatXgProvider.class);
    private final LineupFormAdjustmentService service =
        new LineupFormAdjustmentService(fotMobClient, understatXgProvider);

    private static final TeamXgRating BASE = new TeamXgRating(2.0, 1.0, 6);
    private static final LocalDate MATCH_DATE = LocalDate.of(2026, 9, 20);

    private LineupFormAdjustmentService.MatchContext context(String opponent, boolean isHomeSide) {
        return new LineupFormAdjustmentService.MatchContext(opponent, isHomeSide, MATCH_DATE);
    }

    private void stubTeams() {
        when(fotMobClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(fotMobClient.resolveTeamId("Fulham")).thenReturn(Optional.of(9879));
        when(fotMobClient.fetchFixtures(9825)).thenReturn(List.of(
            new FotMobFixture(5795459, 47, MATCH_DATE, false)
        ));
    }

    @Test
    void passesRatingThroughUnchangedWhenNoContextGiven() {
        assertEquals(BASE, service.adjust("Arsenal", BASE));
    }

    @Test
    void passesRatingThroughUnchangedWhenTeamCannotBeResolved() {
        when(fotMobClient.resolveTeamId("Arsenal")).thenReturn(Optional.empty());

        TeamXgRating result = service.adjust("Arsenal", BASE, context("Fulham", true));

        assertEquals(BASE, result);
    }

    @Test
    void passesRatingThroughUnchangedWhenFixtureCannotBeFound() {
        when(fotMobClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(fotMobClient.resolveTeamId("Fulham")).thenReturn(Optional.of(9879));
        when(fotMobClient.fetchFixtures(9825)).thenReturn(List.of()); // no matching fixture

        TeamXgRating result = service.adjust("Arsenal", BASE, context("Fulham", true));

        assertEquals(BASE, result);
    }

    // === Unavailable-list path (no confirmed lineup posted yet) ===

    @Test
    void subtractsUnavailableAttackingPlayersMeasuredContribution() {
        stubTeams();
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(new FotMobMatchLineups(
            List.of(), // no confirmed lineup yet
            List.of(new FotMobUnavailablePlayer("Bukayo Saka", "injury", "2 weeks")),
            List.of(), List.of()
        ));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of(
            "Bukayo Saka", new PlayerXgContribution("Bukayo Saka", false, 3.6, 360) // 0.9 xG per 90
        ));

        TeamXgRating result = service.adjust("Arsenal", BASE, context("Fulham", true));

        assertEquals(BASE.avgXgFor() - 0.9, result.avgXgFor(), 0.0001);
    }

    @Test
    void appliesFlatPenaltyForUnavailableDefensivePlayer() {
        stubTeams();
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(new FotMobMatchLineups(
            List.of(),
            List.of(new FotMobUnavailablePlayer("William Saliba", "injury", "Mid October")),
            List.of(), List.of()
        ));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of(
            "William Saliba", new PlayerXgContribution("William Saliba", true, 0.1, 540)
        ));

        TeamXgRating result = service.adjust("Arsenal", BASE, context("Fulham", true));

        assertEquals(BASE.avgXgAgainst() + 0.12, result.avgXgAgainst(), 0.0001);
    }

    @Test
    void ignoresUnavailablePlayerThatCannotBeMatchedToAnyUnderstatData() {
        stubTeams();
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(new FotMobMatchLineups(
            List.of(),
            List.of(new FotMobUnavailablePlayer("Completely Unknown Player", "injury", "Illness")),
            List.of(), List.of()
        ));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of(
            "Bukayo Saka", new PlayerXgContribution("Bukayo Saka", false, 0.9, 90)
        ));

        TeamXgRating result = service.adjust("Arsenal", BASE, context("Fulham", true));

        assertEquals(BASE, result);
    }

    @Test
    void matchesUnavailablePlayerBySurnameWhenExactNameDiffers() {
        stubTeams();
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(new FotMobMatchLineups(
            List.of(),
            List.of(new FotMobUnavailablePlayer("B. Saka", "injury", "2 weeks")),
            List.of(), List.of()
        ));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of(
            "Bukayo Saka", new PlayerXgContribution("Bukayo Saka", false, 0.9, 90)
        ));

        TeamXgRating result = service.adjust("Arsenal", BASE, context("Fulham", true));

        assertEquals(BASE.avgXgFor() - 0.9, result.avgXgFor(), 0.0001);
    }

    @Test
    void usesAwaySideDataWhenPlayingAway() {
        when(fotMobClient.resolveTeamId("Fulham")).thenReturn(Optional.of(9879));
        when(fotMobClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(fotMobClient.fetchFixtures(9879)).thenReturn(List.of(
            new FotMobFixture(5795459, 47, MATCH_DATE, false)
        ));
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(new FotMobMatchLineups(
            List.of(), List.of(),
            List.of(), List.of(new FotMobUnavailablePlayer("Bernd Leno", "injury", "1 week"))
        ));
        when(understatXgProvider.getPlayerContributions("Fulham")).thenReturn(Map.of(
            "Bernd Leno", new PlayerXgContribution("Bernd Leno", true, 0.0, 360)
        ));

        TeamXgRating result = service.adjust("Fulham", BASE, context("Arsenal", false));

        assertEquals(BASE.avgXgAgainst() + 0.12, result.avgXgAgainst(), 0.0001);
    }

    // === Confirmed starting XI path ===

    @Test
    void subtractsRegularPlayerMissingFromConfirmedLineupEvenWhenNotListedUnavailable() {
        stubTeams();
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(new FotMobMatchLineups(
            List.of("David Raya", "William Saliba"), // confirmed XI - Saka not included (e.g. rested, not injured)
            List.of(), // not flagged unavailable anywhere
            List.of(), List.of()
        ));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of(
            "Bukayo Saka", new PlayerXgContribution("Bukayo Saka", false, 3.6, 360),
            "David Raya", new PlayerXgContribution("David Raya", true, 0.0, 360)
        ));

        TeamXgRating result = service.adjust("Arsenal", BASE, context("Fulham", true));

        assertEquals(BASE.avgXgFor() - 0.9, result.avgXgFor(), 0.0001);
    }

    @Test
    void doesNotFlagFringePlayersMissingFromLineupAsAbsences() {
        stubTeams();
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(new FotMobMatchLineups(
            List.of("David Raya"), List.of(), List.of(), List.of()
        ));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of(
            "Fringe Player", new PlayerXgContribution("Fringe Player", false, 0.1, 20) // well under the regular threshold
        ));

        TeamXgRating result = service.adjust("Arsenal", BASE, context("Fulham", true));

        assertEquals(BASE, result);
    }

    @Test
    void ignoresUnavailableListOnceConfirmedLineupIsPosted() {
        // Confirmed starters are the authoritative signal once posted - the unavailable
        // list shouldn't be double-counted on top of it.
        stubTeams();
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(new FotMobMatchLineups(
            List.of("Bukayo Saka"), // Saka DID start
            List.of(new FotMobUnavailablePlayer("Bukayo Saka", "doubtful", "n/a")), // stale/inaccurate doubt tag
            List.of(), List.of()
        ));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of(
            "Bukayo Saka", new PlayerXgContribution("Bukayo Saka", false, 3.6, 360)
        ));

        TeamXgRating result = service.adjust("Arsenal", BASE, context("Fulham", true));

        assertEquals(BASE, result); // Saka started, so no adjustment despite being in "unavailable"
    }

    // === Caching (politeness, not quota management) ===

    @Test
    void cachesWithinTtlAndDoesNotRefetch() {
        stubTeams();
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(FotMobMatchLineups.empty());
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of());

        service.adjust("Arsenal", BASE, context("Fulham", true));
        service.adjust("Arsenal", BASE, context("Fulham", true));

        verify(fotMobClient, times(1)).fetchFixtures(9825);
    }

    @Test
    void refetchesAfterCacheExpires() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        service.nowSupplier = () -> start;

        stubTeams();
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(FotMobMatchLineups.empty());
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of());

        service.adjust("Arsenal", BASE, context("Fulham", true));
        service.nowSupplier = () -> start.plusSeconds(31 * 60); // past the 30-minute TTL
        service.adjust("Arsenal", BASE, context("Fulham", true));

        verify(fotMobClient, times(2)).fetchFixtures(9825);
    }

    @Test
    void differentOpponentsAreCachedSeparately() {
        stubTeams();
        when(fotMobClient.fetchFixtures(9825)).thenReturn(List.of(
            new FotMobFixture(5795459, 47, MATCH_DATE, false),
            new FotMobFixture(6000000, 47, MATCH_DATE, false)
        ));
        when(fotMobClient.resolveTeamId("Chelsea")).thenReturn(Optional.of(8455));
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(FotMobMatchLineups.empty());
        when(fotMobClient.fetchMatchLineups(6000000)).thenReturn(FotMobMatchLineups.empty());
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of());

        service.adjust("Arsenal", BASE, context("Fulham", true));
        service.adjust("Arsenal", BASE, context("Chelsea", true));

        verify(fotMobClient, times(2)).fetchFixtures(9825);
    }
}
