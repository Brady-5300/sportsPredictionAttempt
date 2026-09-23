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

        // homeXG = exp(-0.132558 + 0.131990*2.2 + 0.100313*1.6 + 0.249464*1) ≈ 1.76
        // (see XgService.combineAttackDefense - the fitted Poisson regression formula)
        Optional<Double> homeXg = xgService.calculateHomeXG("Arsenal", "Fulham");
        assertTrue(homeXg.isPresent());
        assertEquals(1.76, homeXg.get(), 0.01);

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

        // awayXG = exp(-0.132558 + 0.131990*1.1 + 0.100313*0.9 + 0.249464*0) ≈ 1.11
        Optional<Double> awayXg = xgService.calculateAwayXG("Arsenal", "Fulham");
        assertTrue(awayXg.isPresent());
        assertEquals(1.11, awayXg.get(), 0.01);
    }

    @Test
    void sameTeamRatingsScoreHigherAtHomeThanAway() {
        // The fitted formula found a real home-advantage term - a team with
        // identical attack/defense ratings should be predicted to score more
        // at home than away, all else equal.
        double homeXg = XgService.combineAttackDefense(1.5, 1.2, true);
        double awayXg = XgService.combineAttackDefense(1.5, 1.2, false);

        assertTrue(homeXg > awayXg, "identical ratings should still predict higher xG at home (" + homeXg + " vs " + awayXg + ")");
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
        // exp(-0.132558 + 0.131990*3.0 + 0.100313*1.0 + 0.249464*1) ≈ 1.85
        assertTrue(homeXg.isPresent());
        assertEquals(1.85, homeXg.get(), 0.01);

        // Sanity check the direction is still right even though the exact number changed:
        // boosting attack from 2.0 to 3.0 must increase predicted xG, not decrease it.
        double xgWithoutBoost = XgService.combineAttackDefense(2.0, 1.0, true);
        assertTrue(homeXg.get() > xgWithoutBoost);
    }
}
