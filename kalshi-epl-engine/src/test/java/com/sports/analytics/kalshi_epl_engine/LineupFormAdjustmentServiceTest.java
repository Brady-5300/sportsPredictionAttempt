package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private static final double COEFFICIENT = 0.245 / 1.089;

    // Saka: 2.0 xG in 360 minutes = 0.5 per 90, a quarter of the team's 2.0 attack rating.
    private static final Map<String, PlayerXgContribution> SAKA_REGULAR = Map.of(
        "Bukayo Saka", new PlayerXgContribution("Bukayo Saka", false, 2.0, 360)
    );

    /** Expected attack rating after losing attackers worth this much per-90 xG. */
    private static double expectedFor(double missingPer90Xg) {
        return BASE.avgXgFor() * Math.exp(-COEFFICIENT * missingPer90Xg / BASE.avgXgFor());
    }

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

    private TeamXgRating adjustWith(List<String> starters, List<FotMobUnavailablePlayer> unavailable,
                                    Map<String, PlayerXgContribution> squad) {
        stubTeams();
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(new FotMobMatchLineups(starters, unavailable, List.of(), List.of()));
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(squad);
        return service.adjust("Arsenal", BASE, context("Fulham", true));
    }

    @Test
    void passesRatingThroughUnchangedWhenNoContextGiven() {
        assertEquals(BASE, service.adjust("Arsenal", BASE));
    }

    @Test
    void passesRatingThroughUnchangedWhenTeamCannotBeResolved() {
        when(fotMobClient.resolveTeamId("Arsenal")).thenReturn(Optional.empty());
        assertEquals(BASE, service.adjust("Arsenal", BASE, context("Fulham", true)));
    }

    @Test
    void passesRatingThroughUnchangedWhenFixtureCannotBeFound() {
        when(fotMobClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(fotMobClient.resolveTeamId("Fulham")).thenReturn(Optional.of(9879));
        when(fotMobClient.fetchFixtures(9825)).thenReturn(List.of());
        assertEquals(BASE, service.adjust("Arsenal", BASE, context("Fulham", true)));
    }

    // === Unavailable-list path (no confirmed lineup posted yet) ===

    @Test
    void reducesAttackByTheFittedAmountForAMissingRegularAttacker() {
        TeamXgRating result = adjustWith(List.of(),
            List.of(new FotMobUnavailablePlayer("Bukayo Saka", "injury", "2 weeks")), SAKA_REGULAR);

        assertEquals(expectedFor(0.5), result.avgXgFor(), 1e-9);
        assertEquals(BASE.avgXgAgainst(), result.avgXgAgainst(), 1e-9);
    }

    @Test
    void ignoresMissingDefendersSinceTheyShowedNoMeasurableEffect() {
        TeamXgRating result = adjustWith(List.of(),
            List.of(new FotMobUnavailablePlayer("William Saliba", "injury", "Late October 2026")),
            Map.of("William Saliba", new PlayerXgContribution("William Saliba", true, 0.1, 540)));

        assertEquals(BASE, result);
    }

    @Test
    void ignoresUnavailablePlayerThatCannotBeMatchedToAnyUnderstatData() {
        TeamXgRating result = adjustWith(List.of(),
            List.of(new FotMobUnavailablePlayer("Completely Unknown Player", "injury", "Illness")), SAKA_REGULAR);
        assertEquals(BASE, result);
    }

    @Test
    void matchesUnavailablePlayerBySurnameWhenExactNameDiffers() {
        TeamXgRating result = adjustWith(List.of(),
            List.of(new FotMobUnavailablePlayer("B. Saka", "injury", "2 weeks")), SAKA_REGULAR);
        assertEquals(expectedFor(0.5), result.avgXgFor(), 1e-9);
    }

    @Test
    void usesAwaySideDataWhenPlayingAway() {
        when(fotMobClient.resolveTeamId("Fulham")).thenReturn(Optional.of(9879));
        when(fotMobClient.resolveTeamId("Arsenal")).thenReturn(Optional.of(9825));
        when(fotMobClient.fetchFixtures(9879)).thenReturn(List.of(new FotMobFixture(5795459, 47, MATCH_DATE, false)));
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(new FotMobMatchLineups(
            List.of(), List.of(),
            List.of(), List.of(new FotMobUnavailablePlayer("Raul Jimenez", "injury", "1 week"))
        ));
        when(understatXgProvider.getPlayerContributions("Fulham")).thenReturn(Map.of(
            "Raul Jimenez", new PlayerXgContribution("Raul Jimenez", false, 2.0, 360)
        ));

        TeamXgRating result = service.adjust("Fulham", BASE, context("Arsenal", false));

        assertEquals(expectedFor(0.5), result.avgXgFor(), 1e-9);
    }

    @Test
    void ignoresPlayersListedAsDoubtful() {
        assertEquals(BASE, adjustWith(List.of(),
            List.of(new FotMobUnavailablePlayer("Bukayo Saka", "injury", "Doubtful")), SAKA_REGULAR));
    }

    @Test
    void ignoresPlayersExpectedBackBeforeTheMatch() {
        // Match is 2026-09-20; "Early September 2026" means back from about Sep 1.
        assertEquals(BASE, adjustWith(List.of(),
            List.of(new FotMobUnavailablePlayer("Bukayo Saka", "injury", "Early September 2026")), SAKA_REGULAR));
    }

    @Test
    void countsPlayersExpectedBackAfterTheMatch() {
        TeamXgRating result = adjustWith(List.of(),
            List.of(new FotMobUnavailablePlayer("Bukayo Saka", "injury", "Late October 2026")), SAKA_REGULAR);
        assertEquals(expectedFor(0.5), result.avgXgFor(), 1e-9);
    }

    @Test
    void onlyNearEverPresentPlayersCount() {
        // 250 minutes vs. the squad's most-used player's 360 is under the 80% bar.
        TeamXgRating result = adjustWith(List.of(),
            List.of(new FotMobUnavailablePlayer("Rotation Player", "injury", "Late October 2026")), Map.of(
                "David Raya", new PlayerXgContribution("David Raya", true, 0.0, 360),
                "Rotation Player", new PlayerXgContribution("Rotation Player", false, 1.0, 250)
            ));
        assertEquals(BASE, result);
    }

    @Test
    void multipleAbsencesCompoundButNeverDriveAttackToZero() {
        TeamXgRating result = adjustWith(List.of(), List.of(
                new FotMobUnavailablePlayer("Attacker One", "injury", "Late October 2026"),
                new FotMobUnavailablePlayer("Attacker Two", "injury", "Late October 2026")),
            Map.of(
                "Attacker One", new PlayerXgContribution("Attacker One", false, 4.0, 360), // 1.0 per 90
                "Attacker Two", new PlayerXgContribution("Attacker Two", false, 4.0, 360)  // 1.0 per 90
            ));

        // Losing the entire 2.0 attack rating's worth still only cuts it by ~21%.
        assertEquals(expectedFor(2.0), result.avgXgFor(), 1e-9);
        assertTrue(result.avgXgFor() > 1.5);
    }

    @Test
    void parsesFotMobExpectedReturnText() {
        assertEquals(Optional.of(LocalDate.of(2026, 10, 11)), LineupFormAdjustmentService.earliestReturnDate("Mid October 2026"));
        assertEquals(Optional.of(LocalDate.of(2027, 4, 1)), LineupFormAdjustmentService.earliestReturnDate("Early April 2027"));
        assertEquals(Optional.of(LocalDate.of(2026, 9, 21)), LineupFormAdjustmentService.earliestReturnDate("Late September 2026"));
        assertTrue(LineupFormAdjustmentService.earliestReturnDate("2 weeks").isEmpty());
    }

    // === Confirmed starting XI path ===

    @Test
    void countsRegularMissingFromConfirmedLineupEvenWhenNotListedUnavailable() {
        TeamXgRating result = adjustWith(List.of("David Raya", "William Saliba"), List.of(), Map.of(
            "Bukayo Saka", new PlayerXgContribution("Bukayo Saka", false, 2.0, 360),
            "David Raya", new PlayerXgContribution("David Raya", true, 0.0, 360)
        ));
        assertEquals(expectedFor(0.5), result.avgXgFor(), 1e-9);
    }

    @Test
    void doesNotFlagFringePlayersMissingFromLineupAsAbsences() {
        assertEquals(BASE, adjustWith(List.of("David Raya"), List.of(), Map.of(
            "David Raya", new PlayerXgContribution("David Raya", true, 0.0, 360),
            "Fringe Player", new PlayerXgContribution("Fringe Player", false, 0.1, 20)
        )));
    }

    @Test
    void ignoresUnavailableListOnceConfirmedLineupIsPosted() {
        // Saka started, so no adjustment despite a stale "unavailable" entry.
        assertEquals(BASE, adjustWith(List.of("Bukayo Saka"),
            List.of(new FotMobUnavailablePlayer("Bukayo Saka", "doubtful", "n/a")), SAKA_REGULAR));
    }

    @Test
    void reportsWhetherTheConfirmedLineupIsPosted() {
        stubTeams();
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(SAKA_REGULAR);
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(new FotMobMatchLineups(
            List.of("Bukayo Saka"), List.of(), List.of(), List.of()));

        assertTrue(service.hasConfirmedLineup("Arsenal", context("Fulham", true)));
    }

    @Test
    void noConfirmedLineupWhileOnlyTheUnavailableListExists() {
        stubTeams();
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(SAKA_REGULAR);
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(new FotMobMatchLineups(
            List.of(), List.of(new FotMobUnavailablePlayer("Bukayo Saka", "injury", "2 weeks")), List.of(), List.of()));

        assertFalse(service.hasConfirmedLineup("Arsenal", context("Fulham", true)));
        assertFalse(service.hasConfirmedLineup("Arsenal", null));
    }

    // === Caching ===

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
        Instant start = Instant.parse("2026-01-01T00:00:00Z"); // not match day: 30-minute cache
        service.nowSupplier = () -> start;
        stubTeams();
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(FotMobMatchLineups.empty());
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of());

        service.adjust("Arsenal", BASE, context("Fulham", true));
        service.nowSupplier = () -> start.plusSeconds(10 * 60);
        service.adjust("Arsenal", BASE, context("Fulham", true));
        verify(fotMobClient, times(1)).fetchFixtures(9825);

        service.nowSupplier = () -> start.plusSeconds(31 * 60);
        service.adjust("Arsenal", BASE, context("Fulham", true));
        verify(fotMobClient, times(2)).fetchFixtures(9825);
    }

    @Test
    void refreshesEveryScanOnMatchDaySoLineupsAreNoticedQuickly() {
        Instant matchDayMorning = Instant.parse("2026-09-20T10:00:00Z");
        service.nowSupplier = () -> matchDayMorning;
        stubTeams();
        when(fotMobClient.fetchMatchLineups(5795459)).thenReturn(FotMobMatchLineups.empty());
        when(understatXgProvider.getPlayerContributions("Arsenal")).thenReturn(Map.of());

        service.adjust("Arsenal", BASE, context("Fulham", true));
        service.nowSupplier = () -> matchDayMorning.plusSeconds(5 * 60); // next scan
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
