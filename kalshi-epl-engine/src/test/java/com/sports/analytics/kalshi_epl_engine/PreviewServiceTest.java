package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PreviewServiceTest {

    private final XgService xgService = mock(XgService.class);
    private final UnderstatXgProvider provider = mock(UnderstatXgProvider.class);
    private final PoissonModel poisson = new PoissonModel();
    private final PreviewService service = new PreviewService(xgService, provider, poisson, new TeamNameResolver());

    private MarketEvaluation market(String ticker, String title, String type, Integer bid, Integer ask, String kickoff) {
        return new MarketEvaluation(ticker, title, ask == null ? 0 : ask, "", "", "", "FAIR VALUE", 0.0, "understat-live",
            kickoff, type, bid, ask);
    }

    private List<MarketEvaluation> arsenalLeeds(String kickoff) {
        return List.of(
            market("KXEPLGAME-26OCT10ARSLEE-ARS", "Arsenal vs Leeds United: Arsenal wins", "HOME", 71, 73, kickoff),
            market("KXEPLGAME-26OCT10ARSLEE-TIE", "Arsenal vs Leeds United: Tie is the result", "TIE", 17, 19, kickoff),
            market("KXEPLGAME-26OCT10ARSLEE-LEE", "Arsenal vs Leeds United: Leeds United wins", "AWAY", 11, 13, kickoff));
    }

    private void stubTeams() {
        when(xgService.calculateHomeXG("Arsenal", "Leeds United")).thenReturn(Optional.of(1.9));
        when(xgService.calculateAwayXG("Arsenal", "Leeds United")).thenReturn(Optional.of(0.9));
        when(provider.getRating("Arsenal")).thenReturn(Optional.of(new TeamXgRating(1.7, 0.9, 30)));
        when(provider.getRating("Leeds United")).thenReturn(Optional.of(new TeamXgRating(1.3, 1.5, 30)));
        when(provider.getCurrentLeagueRatings()).thenReturn(Map.of(
            "Arsenal", new TeamXgRating(1.7, 0.9, 30),
            "Leeds", new TeamXgRating(1.3, 1.5, 30),
            "Manchester_City", new TeamXgRating(1.9, 1.1, 30)));
        when(provider.getRecentForm(anyString(), anyInt())).thenReturn(List.of());
        when(provider.getPlayerContributions("Arsenal")).thenReturn(Map.of(
            "Striker", new PlayerXgContribution("Striker", false, 3.0, 450),       // 0.60 per 90
            "Winger", new PlayerXgContribution("Winger", false, 2.0, 450),         // 0.40 per 90
            "Super Sub", new PlayerXgContribution("Super Sub", false, 1.0, 60),    // 1.50 per 90, too few minutes
            "Centre Back", new PlayerXgContribution("Centre Back", true, 1.2, 450)));
        when(provider.getPlayerContributions("Leeds United")).thenReturn(Map.of());
    }

    @Test
    void buildsWinDrawLossLikelyScoresAndGoalMarkets() {
        stubTeams();
        MatchPreview p = service.build(arsenalLeeds("2026-10-10T11:30:00Z")).get(0);

        assertEquals("Arsenal", p.home());
        assertEquals("Leeds United", p.away());
        assertEquals(1.0, p.homeWin() + p.draw() + p.awayWin(), 1e-3);
        assertEquals(poisson.calculateMarketProbability(1.9, 0.9, "HOME"), p.homeWin(), 1e-3);
        assertEquals(5, p.likelyScores().size());
        assertTrue(p.likelyScores().get(0).probability() >= p.likelyScores().get(4).probability());
        // Both score = (1 - P(home 0)) * (1 - P(away 0)) for independent Poisson goals.
        assertEquals((1 - Math.exp(-1.9)) * (1 - Math.exp(-0.9)), p.bothTeamsScore(), 1e-3);
        double underThree = 0;
        for (int h = 0; h <= 2; h++) for (int a = 0; a <= 2 - h; a++) underThree += Math.exp(-2.8) * Math.pow(1.9, h) * Math.pow(0.9, a) / (fact(h) * fact(a));
        assertEquals(1 - underThree, p.overTwoAndHalfGoals(), 1e-3);
    }

    @Test
    void showsKalshisOddsScaledToAddUpToOneHundredPercent() {
        stubTeams();
        MatchPreview p = service.build(arsenalLeeds("2026-10-10T11:30:00Z")).get(0);

        // Midpoints 72, 18, 12 add to 102, so each is divided by 1.02.
        assertEquals(0.72 / 1.02, p.kalshiHomeWin(), 1e-3);
        assertEquals(0.18 / 1.02, p.kalshiDraw(), 1e-3);
        assertEquals(1.0, p.kalshiHomeWin() + p.kalshiDraw() + p.kalshiAwayWin(), 1e-3);
    }

    @Test
    void ranksTeamsAndPicksRegularAttackersAsThreats() {
        stubTeams();
        MatchPreview p = service.build(arsenalLeeds("2026-10-10T11:30:00Z")).get(0);

        assertEquals(2, p.homeTeam().attackRank());   // behind Man City's 1.9
        assertEquals(1, p.homeTeam().defenseRank());  // 0.9 conceded is the league's best
        assertEquals(3, p.awayTeam().defenseRank());
        assertEquals(List.of("Striker", "Winger"), p.homeTeam().threats().stream().map(MatchPreview.Threat::player).toList());
        assertEquals(0.6, p.homeTeam().threats().get(0).xgPer90(), 1e-9);
    }

    @Test
    void leavesOutKalshiOddsWithoutAUsablePrice() {
        stubTeams();
        List<MarketEvaluation> wideSpreads = List.of(
            market("KXEPLGAME-26OCT10ARSLEE-ARS", "Arsenal vs Leeds United: Arsenal wins", "HOME", 40, 80, null),
            market("KXEPLGAME-26OCT10ARSLEE-TIE", "Arsenal vs Leeds United: Tie is the result", "TIE", 17, 19, null),
            market("KXEPLGAME-26OCT10ARSLEE-LEE", "Arsenal vs Leeds United: Leeds United wins", "AWAY", 11, 13, null));

        MatchPreview p = service.build(wideSpreads).get(0);

        assertNull(p.kalshiHomeWin());
        assertNull(p.kalshiDraw());
    }

    @Test
    void skipsMatchesWithoutRatings() {
        when(xgService.calculateHomeXG(anyString(), anyString())).thenReturn(Optional.empty());
        when(xgService.calculateAwayXG(anyString(), anyString())).thenReturn(Optional.empty());
        when(provider.getCurrentLeagueRatings()).thenReturn(Map.of());

        assertTrue(service.build(arsenalLeeds(null)).isEmpty());
    }

    @Test
    void readsTeamsFromKalshiTitles() {
        assertArrayEquals(new String[]{"Arsenal", "Leeds United"}, PreviewService.teamsOf("Arsenal vs Leeds United: Leeds United wins"));
        assertArrayEquals(new String[]{"Aston Villa", "Arsenal"}, PreviewService.teamsOf("Aston Villa vs Arsenal Winner?"));
        assertNull(PreviewService.teamsOf("Something else entirely"));
    }

    private static double fact(int n) {
        return n <= 1 ? 1 : n * fact(n - 1);
    }
}
