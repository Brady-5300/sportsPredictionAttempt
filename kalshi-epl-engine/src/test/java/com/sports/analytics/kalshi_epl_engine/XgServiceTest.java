package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class XgServiceTest {

    private final TeamNameResolver resolver = new TeamNameResolver();
    private final UnderstatXgProvider provider = mock(UnderstatXgProvider.class);
    private final LineupFormAdjustmentService adjuster = mock(LineupFormAdjustmentService.class);
    private final XgService xgService = new XgService(resolver, provider, adjuster);

    XgServiceTest() {
        // Pass the base rating through unchanged by default, so existing arithmetic
        // assertions (written before the adjustment layer existed) still hold; tests
        // that specifically want to exercise the adjustment stub it explicitly.
        when(adjuster.adjust(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    void usesLiveRatingsWhenBothTeamsHaveThem() {
        when(provider.getRating("Arsenal")).thenReturn(Optional.of(new TeamXgRating(2.2, 0.9, 6)));
        when(provider.getRating("Fulham")).thenReturn(Optional.of(new TeamXgRating(1.1, 1.6, 6)));

        // homeXG = homeAttack(2.2)*0.6 + (2.0 - awayDefense(1.6))*0.4 = 1.32 + 0.16 = 1.48
        Optional<Double> homeXg = xgService.calculateHomeXG("Arsenal", "Fulham");
        assertTrue(homeXg.isPresent());
        assertEquals(1.48, homeXg.get(), 0.001);

        assertTrue(xgService.hasLiveDataFor("Arsenal", "Fulham"));
    }

    @Test
    void returnsEmptyWhenLiveDataUnavailableForOneTeamRatherThanFallingBackToAStaticGuess() {
        when(provider.getRating("Arsenal")).thenReturn(Optional.empty());
        when(provider.getRating("Some Newly Promoted Club")).thenReturn(Optional.empty());

        Optional<Double> homeXg = xgService.calculateHomeXG("Arsenal", "Some Newly Promoted Club");
        assertTrue(homeXg.isEmpty(), "should return empty rather than a static-table guess when live data is missing");

        assertFalse(xgService.hasLiveDataFor("Arsenal", "Some Newly Promoted Club"));
    }

    @Test
    void returnsEmptyWhenOnlyOneSideHasLiveData() {
        when(provider.getRating("Arsenal")).thenReturn(Optional.of(new TeamXgRating(2.2, 0.9, 6)));
        when(provider.getRating("Some Newly Promoted Club")).thenReturn(Optional.empty());

        assertTrue(xgService.calculateHomeXG("Arsenal", "Some Newly Promoted Club").isEmpty());
        assertTrue(xgService.calculateAwayXG("Arsenal", "Some Newly Promoted Club").isEmpty());
    }

    @Test
    void awayXgUsesAwayTeamAttackAndHomeTeamDefense() {
        when(provider.getRating("Arsenal")).thenReturn(Optional.of(new TeamXgRating(2.2, 0.9, 6)));
        when(provider.getRating("Fulham")).thenReturn(Optional.of(new TeamXgRating(1.1, 1.6, 6)));

        // awayXG = awayAttack(1.1)*0.6 + (2.0 - homeDefense(0.9))*0.4 = 0.66 + 0.44 = 1.10
        Optional<Double> awayXg = xgService.calculateAwayXG("Arsenal", "Fulham");
        assertTrue(awayXg.isPresent());
        assertEquals(1.10, awayXg.get(), 0.001);
    }

    @Test
    void teamCodeDelegatesToResolver() {
        assertEquals("ARS", xgService.getTeamCode("Arsenal"));
    }

    @Test
    void usesLineupFormAdjustedRatingRatherThanRawBaseRating() {
        when(provider.getRating("Arsenal")).thenReturn(Optional.of(new TeamXgRating(2.0, 1.0, 6)));
        when(provider.getRating("Fulham")).thenReturn(Optional.of(new TeamXgRating(1.0, 1.0, 6)));

        // Simulate the adjuster boosting Arsenal's attack (e.g. a missing opposing defender elsewhere).
        when(adjuster.adjust(eq("Arsenal"), eq(new TeamXgRating(2.0, 1.0, 6)), any()))
            .thenReturn(new TeamXgRating(3.0, 1.0, 6));

        Optional<Double> homeXg = xgService.calculateHomeXG("Arsenal", "Fulham");

        // homeXG should reflect the ADJUSTED attack (3.0), not the raw base (2.0):
        // 3.0*0.6 + (2.0 - 1.0)*0.4 = 1.8 + 0.4 = 2.2
        assertTrue(homeXg.isPresent());
        assertEquals(2.2, homeXg.get(), 0.001);
    }
}
