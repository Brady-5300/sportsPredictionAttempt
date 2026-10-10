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
            kickoff, type, bid, ask, false);
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
        UnderstatXgProvider.SeasonStats arsenal = new UnderstatXgProvider.SeasonStats(5, 4, 0, 1, 9, 3, 1.7, 0.9);
        UnderstatXgProvider.SeasonStats leeds = new UnderstatXgProvider.SeasonStats(5, 2, 3, 0, 7, 5, 1.3, 1.5);
        when(provider.getSeasonStats("Arsenal")).thenReturn(Optional.of(arsenal));
        when(provider.getSeasonStats("Leeds United")).thenReturn(Optional.of(leeds));
        when(provider.getCurrentLeagueSeasonStats()).thenReturn(Map.of(
            "Arsenal", arsenal,
            "Leeds", leeds,
            "Manchester_City", new UnderstatXgProvider.SeasonStats(5, 3, 1, 1, 12, 6, 1.9, 1.1)));
        when(provider.getRecentForm(anyString(), anyInt())).thenReturn(List.of());
        when(provider.getSeasonPlayers("Arsenal")).thenReturn(List.of(
            new UnderstatPlayerSeason("Striker", "Arsenal", "F", 5, 450, 3, 3, 2.5, 0.7),     // 6 G+A
            new UnderstatPlayerSeason("Winger", "Arsenal", "M", 5, 450, 2, 1, 1.0, 0.8),      // 3 G+A
            new UnderstatPlayerSeason("Centre Back", "Arsenal", "D", 5, 450, 1, 2, 0.4, 0.1), // 3 G+A, less xG
            new UnderstatPlayerSeason("Super Sub", "Arsenal", "F", 2, 60, 2, 0, 0.9, 0.0)));  // too few minutes
        when(provider.getSeasonPlayers("Leeds United")).thenReturn(List.of());
    }

    @Test
    void buildsWinDrawLossAndGoalMarkets() {
        stubTeams();
        MatchPreview p = service.build(arsenalLeeds("2026-10-10T11:30:00Z")).get(0);

        assertEquals("Arsenal", p.home());
        assertEquals("Leeds United", p.away());
        assertEquals(1.0, p.homeWin() + p.draw() + p.awayWin(), 1e-3);
        assertEquals(poisson.calculateMarketProbability(1.9, 0.9, "HOME"), p.homeWin(), 1e-3);
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
    void showsThisSeasonsStatsRankedAgainstTheLeagueAndRegularAttackersAsThreats() {
        stubTeams();
        MatchPreview p = service.build(arsenalLeeds("2026-10-10T11:30:00Z")).get(0);

        assertEquals(4, p.homeTeam().season().wins());
        assertEquals(2, p.homeTeam().xgForRank());      // behind Man City's 1.9 per match
        assertEquals(1, p.homeTeam().xgAgainstRank());  // 0.9 conceded per match is the league's best
        assertEquals(3, p.awayTeam().xgAgainstRank());
        // Ranked by goals + assists; Winger and Centre Back tie on 3, Winger has more xG + xA per 90.
        assertEquals(List.of("Striker", "Winger", "Centre Back"), p.homeTeam().threats().stream().map(MatchPreview.Threat::player).toList());
        MatchPreview.Threat top = p.homeTeam().threats().get(0);
        assertEquals(3, top.goals());
        assertEquals(3, top.assists());
        assertEquals(0.5, top.xgPer90(), 1e-9); // 2.5 xG over 450 minutes
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
        when(provider.getCurrentLeagueSeasonStats()).thenReturn(Map.of());

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

    @Test
    void leavesOutMatchesThatHaveKickedOff() {
        stubTeams();
        List<MarketEvaluation> live = List.of(
            new MarketEvaluation("KXEPLGAME-26OCT10ARSLEE-ARS", "Arsenal vs Leeds United: Arsenal wins", 95, "", "95.0%", "",
                "LIVE", 0.0, "kalshi-live", "2026-10-10T11:30:00Z", "HOME", 94, 96, true));

        assertTrue(service.build(live).isEmpty());
    }
}
