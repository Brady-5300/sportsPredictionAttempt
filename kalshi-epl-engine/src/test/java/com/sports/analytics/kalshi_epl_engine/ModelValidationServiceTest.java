package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelValidationServiceTest {

    private final KalshiHistoricalClient historicalClient = mock(KalshiHistoricalClient.class);
    private final TickerParserService tickerParserService = new TickerParserService();
    private final UnderstatXgProvider understatXgProvider = mock(UnderstatXgProvider.class);
    private final PoissonModel poissonModel = new PoissonModel();
    private final KalshiMarketService kalshiMarketService = mock(KalshiMarketService.class);

    private final ModelValidationService service = new ModelValidationService(
        historicalClient, tickerParserService, understatXgProvider, poissonModel, kalshiMarketService
    );

    private KalshiMarket market(String ticker, String title, String result) {
        KalshiMarket m = new KalshiMarket();
        m.setTicker(ticker);
        m.setTitle(title);
        m.setResult(result);
        return m;
    }

    private KalshiEvent event(String title, KalshiMarket... markets) {
        KalshiEvent e = new KalshiEvent();
        e.setTitle(title);
        e.setMarkets(List.of(markets));
        return e;
    }

    @Test
    void skipsMarketsWithNoSettledResult() {
        KalshiMarket unsettled = market("KXEPLGAME-26SEP20FULMUN-FUL", "Fulham wins", null);
        when(historicalClient.fetchSettledEvents(10)).thenReturn(List.of(event("Fulham vs Manchester United", unsettled)));

        ModelValidationReport report = service.run(10);

        assertEquals(0, report.marketsTotal());
        assertEquals(0, report.predictionsEvaluated());
    }

    @Test
    void skipsMarketsWithNoLiveXgDataAndCountsThemSeparately() {
        KalshiMarket m = market("KXEPLGAME-26SEP20FULMUN-FUL", "Fulham wins", "no");
        when(historicalClient.fetchSettledEvents(10)).thenReturn(List.of(event("Fulham vs Manchester United", m)));
        when(understatXgProvider.getRatingAsOf(anyString(), any())).thenReturn(Optional.empty());

        ModelValidationReport report = service.run(10);

        assertEquals(1, report.marketsTotal());
        assertEquals(1, report.skippedNoXgData());
        assertEquals(0, report.predictionsEvaluated());
    }

    @Test
    void recordsAPredictionWithRealOutcomeWhenDataIsAvailable() {
        KalshiMarket m = market("KXEPLGAME-26SEP20FULMUN-FUL", "Fulham wins", "no");
        when(historicalClient.fetchSettledEvents(10)).thenReturn(List.of(event("Fulham vs Manchester United", m)));
        when(understatXgProvider.getRatingAsOf(eq("Fulham"), any())).thenReturn(Optional.of(new TeamXgRating(1.2, 1.3, 6)));
        when(understatXgProvider.getRatingAsOf(eq("Manchester United"), any())).thenReturn(Optional.of(new TeamXgRating(1.8, 1.0, 6)));
        when(kalshiMarketService.resolveMarketType(eq(m), anyString(), anyString(), anyString())).thenReturn("HOME");

        ModelValidationReport report = service.run(10);

        assertEquals(1, report.predictionsEvaluated());
        PredictionRecord prediction = report.predictions().get(0);
        assertEquals("HOME", prediction.marketType());
        assertTrue(!prediction.actualOutcome()); // result was "no"
        assertTrue(prediction.predictedProbability() > 0 && prediction.predictedProbability() < 1);
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
