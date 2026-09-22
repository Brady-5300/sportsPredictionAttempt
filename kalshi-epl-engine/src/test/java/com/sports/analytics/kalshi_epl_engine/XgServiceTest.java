package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class XgServiceTest {

    private final TeamNameResolver resolver = new TeamNameResolver();
    private final UnderstatXgProvider provider = mock(UnderstatXgProvider.class);
    private final XgService xgService = new XgService(resolver, provider);

    @Test
    void usesLiveRatingsWhenBothTeamsHaveThem() {
        when(provider.getRating("Arsenal")).thenReturn(Optional.of(new TeamXgRating(2.2, 0.9, 6)));
        when(provider.getRating("Fulham")).thenReturn(Optional.of(new TeamXgRating(1.1, 1.6, 6)));

        // homeXG = homeAttack(2.2)*0.6 + (2.0 - awayDefense(1.6))*0.4 = 1.32 + 0.16 = 1.48
        double homeXg = xgService.calculateHomeXG("Arsenal", "Fulham");
        assertEquals(1.48, homeXg, 0.001);

        assertTrue(xgService.hasLiveDataFor("Arsenal", "Fulham"));
    }

    @Test
    void fallsBackToStaticTableWhenLiveDataUnavailableForOneTeam() {
        when(provider.getRating("Arsenal")).thenReturn(Optional.empty());
        when(provider.getRating("Some Newly Promoted Club")).thenReturn(Optional.empty());

        // Should not throw, and should not report live data available.
        double homeXg = xgService.calculateHomeXG("Arsenal", "Some Newly Promoted Club");
        assertTrue(homeXg > 0);

        assertFalse(xgService.hasLiveDataFor("Arsenal", "Some Newly Promoted Club"));
    }

    @Test
    void awayXgUsesAwayTeamAttackAndHomeTeamDefense() {
        when(provider.getRating("Arsenal")).thenReturn(Optional.of(new TeamXgRating(2.2, 0.9, 6)));
        when(provider.getRating("Fulham")).thenReturn(Optional.of(new TeamXgRating(1.1, 1.6, 6)));

        // awayXG = awayAttack(1.1)*0.6 + (2.0 - homeDefense(0.9))*0.4 = 0.66 + 0.44 = 1.10
        double awayXg = xgService.calculateAwayXG("Arsenal", "Fulham");
        assertEquals(1.10, awayXg, 0.001);
    }

    @Test
    void teamCodeDelegatesToResolver() {
        assertEquals("ARS", xgService.getTeamCode("Arsenal"));
    }
}
