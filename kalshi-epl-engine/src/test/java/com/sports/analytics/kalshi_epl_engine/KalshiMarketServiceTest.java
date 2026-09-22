package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KalshiMarketServiceTest {

    private final KalshiMarketService service =
        new KalshiMarketService(new PoissonModel(), new TickerParserService(), new XgService());

    private KalshiMarket marketWithTicker(String ticker) {
        KalshiMarket market = new KalshiMarket();
        market.setTicker(ticker);
        return market;
    }

    @Test
    void exactSuffixMatchOnAwayCodeResolvesToAway() {
        KalshiMarket market = marketWithTicker("KXEPLGAME-26SEP20FULMUN-MUN");
        String type = service.resolveMarketType(market, "Manchester United", "Fulham", "Manchester United");
        assertEquals("AWAY", type);
    }

    @Test
    void exactSuffixMatchOnHomeCodeResolvesToHome() {
        KalshiMarket market = marketWithTicker("KXEPLGAME-26SEP20FULMUN-FUL");
        String type = service.resolveMarketType(market, "Fulham", "Fulham", "Manchester United");
        assertEquals("HOME", type);
    }

    @Test
    void tieTickerSuffixResolvesToTie() {
        KalshiMarket market = marketWithTicker("KXEPLGAME-26SEP20FULMUN-TIE");
        String type = service.resolveMarketType(market, "Draw", "Fulham", "Manchester United");
        assertEquals("TIE", type);
    }

    @Test
    void shortTieTickerSuffixResolvesToTie() {
        KalshiMarket market = marketWithTicker("KXEPLGAME-26SEP20FULMUN-T");
        String type = service.resolveMarketType(market, "Draw", "Fulham", "Manchester United");
        assertEquals("TIE", type);
    }

    @Test
    void drawKeywordInTitleResolvesToTieRegardlessOfTicker() {
        KalshiMarket market = marketWithTicker("KXEPLGAME-26SEP20FULMUN-FUL");
        String type = service.resolveMarketType(market, "Match ends in a draw", "Fulham", "Manchester United");
        assertEquals("TIE", type);
    }

    @Test
    void partialSuffixOverlapDoesNotFalsePositiveMatchEitherTeam() {
        // "MU" is a substring of MUN's code but not an exact match to either team's code -
        // exact-match logic must not treat this as Manchester United, and must fall through
        // to the title-text fallback instead of guessing wrong.
        KalshiMarket market = marketWithTicker("KXEPLGAME-26SEP20FULMUN-MU");
        String type = service.resolveMarketType(market, "Fulham vs Manchester United: Fulham wins", "Fulham", "Manchester United");
        assertEquals("HOME", type);
    }

    @Test
    void unrecognizedTeamFallsBackToTitleTextMatching() {
        KalshiMarket market = marketWithTicker("KXEPLGAME-26SEP20XXXFUL-XXX");
        String type = service.resolveMarketType(market, "Some Newly Promoted Club vs Fulham: Some Newly Promoted Club wins", "Some Newly Promoted Club", "Fulham");
        assertEquals("HOME", type);
    }

    @Test
    void ambiguousUnresolvableCaseDefaultsToHome() {
        KalshiMarket market = marketWithTicker("KXEPLGAME-26SEP20XXXYYY-ZZZ");
        String type = service.resolveMarketType(market, "Ambiguous title mentioning neither team clearly", "Team X", "Team Y");
        assertEquals("HOME", type);
    }
}
