package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PredictionLogServiceTest {

    private static final Instant KICKOFF = Instant.parse("2026-09-20T13:00:00Z");
    private static final String TICKER = "KXEPLGAME-26SEP20BOULFC-LFC";

    @TempDir
    Path tempDir;

    private final KalshiHistoricalClient client = mock(KalshiHistoricalClient.class);

    private PredictionLogService serviceAt(Instant now) {
        PredictionLogService service = new PredictionLogService(client, tempDir.resolve("log.jsonl"));
        service.nowSupplier = () -> now;
        return service;
    }

    private void record(PredictionLogService service, String ticker, double prob, Integer bid, Integer ask, Optional<Instant> kickoff) {
        recordWithBase(service, ticker, prob, prob, bid, ask, kickoff);
    }

    private void recordWithBase(PredictionLogService service, String ticker, double prob, Double base,
                                Integer bid, Integer ask, Optional<Instant> kickoff) {
        service.recordSnapshot(ticker, "Bournemouth vs Liverpool: Liverpool wins", "AWAY", "2026-09-20", prob, base, bid, ask, kickoff);
    }

    private KalshiMarket settled(String result) {
        KalshiMarket market = new KalshiMarket();
        market.setResult(result);
        return market;
    }

    @Test
    void logsFirstPreKickoffSightingWithMarketPrice() {
        PredictionLogService service = serviceAt(KICKOFF.minusSeconds(86400));
        record(service, TICKER, 0.55, 52, 54, Optional.of(KICKOFF));

        PredictionLogEntry entry = service.allEntries().get(0);
        assertEquals(0.55, entry.predictedProbability());
        assertEquals(52, entry.marketBidCents());
        assertEquals(54, entry.marketAskCents());
        assertEquals(KICKOFF.toString(), entry.kickoff());
    }

    @Test
    void refreshesSnapshotBeforeKickoffButKeepsOriginalLogTime() {
        PredictionLogService service = serviceAt(KICKOFF.minusSeconds(86400));
        record(service, TICKER, 0.55, 52, 54, Optional.of(KICKOFF));
        String firstLoggedAt = service.allEntries().get(0).loggedAt();

        service.nowSupplier = () -> KICKOFF.minusSeconds(600); // 10 minutes before kickoff, lineups known
        record(service, TICKER, 0.48, 47, 49, Optional.of(KICKOFF));

        PredictionLogEntry entry = service.allEntries().get(0);
        assertEquals(0.48, entry.predictedProbability());
        assertEquals(47, entry.marketBidCents());
        assertEquals(firstLoggedAt, entry.loggedAt());
        assertEquals(KICKOFF.minusSeconds(600).toString(), entry.snapshotAt());
    }

    @Test
    void freezesAtKickoffSoInPlayPricesNeverOverwriteThePreMatchSnapshot() {
        PredictionLogService service = serviceAt(KICKOFF.minusSeconds(600));
        record(service, TICKER, 0.48, 47, 49, Optional.of(KICKOFF));

        service.nowSupplier = () -> KICKOFF.plusSeconds(3000); // mid-match, market now pricing in-play
        record(service, TICKER, 0.48, 95, 97, Optional.of(KICKOFF));

        assertEquals(47, service.allEntries().get(0).marketBidCents());
    }

    @Test
    void doesNotLogAMarketFirstSeenAfterKickoff() {
        PredictionLogService service = serviceAt(KICKOFF.plusSeconds(60));
        record(service, TICKER, 0.48, 60, 62, Optional.of(KICKOFF));

        assertTrue(service.allEntries().isEmpty());
    }

    @Test
    void unknownKickoffLogsOnceWithoutAMarketPrice() {
        PredictionLogService service = serviceAt(KICKOFF);
        record(service, TICKER, 0.48, 47, 49, Optional.empty());
        record(service, TICKER, 0.60, 58, 60, Optional.empty());

        PredictionLogEntry entry = service.allEntries().get(0);
        assertEquals(0.48, entry.predictedProbability());
        assertNull(entry.marketBidCents());
        assertNull(entry.marketAskCents());
    }

    @Test
    void persistsAndReloadsFromDisk() {
        PredictionLogService service = serviceAt(KICKOFF.minusSeconds(600));
        record(service, TICKER, 0.48, 47, 49, Optional.of(KICKOFF));

        PredictionLogService reloaded = new PredictionLogService(client, tempDir.resolve("log.jsonl"));

        assertEquals(service.allEntries(), reloaded.allEntries());
    }

    @Test
    void recordsOutcomeOnceKalshiSettlesTheMarket() {
        PredictionLogService service = serviceAt(KICKOFF.minusSeconds(600));
        record(service, TICKER, 0.48, 47, 49, Optional.of(KICKOFF));
        when(client.fetchSingleMarket(TICKER)).thenReturn(Optional.of(settled("yes")));

        assertEquals(1, service.checkForResolutions());

        PredictionLogEntry entry = service.allEntries().get(0);
        assertTrue(entry.resolved());
        assertTrue(entry.actualOutcome());
    }

    @Test
    void comparesModelAndMarketBrierOnTheSameMarketsOnly() {
        PredictionLogService service = serviceAt(KICKOFF.minusSeconds(600));
        // Market with a usable price: model 0.70, market mid 0.50, outcome yes.
        record(service, "KXEPLGAME-26SEP20BOULFC-LFC", 0.70, 49, 51, Optional.of(KICKOFF));
        // Spread too wide to count as a price - excluded from the comparison.
        record(service, "KXEPLGAME-26SEP20BOULFC-BOU", 0.20, 5, 40, Optional.of(KICKOFF));
        when(client.fetchSingleMarket("KXEPLGAME-26SEP20BOULFC-LFC")).thenReturn(Optional.of(settled("yes")));
        when(client.fetchSingleMarket("KXEPLGAME-26SEP20BOULFC-BOU")).thenReturn(Optional.of(settled("no")));
        service.checkForResolutions();

        MarketComparison comparison = service.marketComparison();

        assertEquals(2, comparison.resolvedPredictions());
        assertEquals(1, comparison.comparedPredictions());
        assertEquals(0.09, comparison.modelBrier(), 1e-9);  // (0.70 - 1)^2
        assertEquals(0.25, comparison.marketBrier(), 1e-9); // (0.50 - 1)^2
    }

    @Test
    void refreshesTheNoLineupPredictionAlongsideTheFullOne() {
        PredictionLogService service = serviceAt(KICKOFF.minusSeconds(86400));
        recordWithBase(service, TICKER, 0.55, 0.55, 52, 54, Optional.of(KICKOFF));

        service.nowSupplier = () -> KICKOFF.minusSeconds(600); // lineups posted: a key striker is out
        recordWithBase(service, TICKER, 0.48, 0.55, 47, 49, Optional.of(KICKOFF));

        PredictionLogEntry entry = service.allEntries().get(0);
        assertEquals(0.48, entry.predictedProbability());
        assertEquals(0.55, entry.baseProbability());
    }

    @Test
    void comparesWithAndWithoutLineupsOnTheSameMarkets() {
        PredictionLogService service = serviceAt(KICKOFF.minusSeconds(600));
        // Lineup layer moved this one: 0.70 with lineups vs 0.60 without, outcome yes.
        recordWithBase(service, "KXEPLGAME-26SEP20BOULFC-LFC", 0.70, 0.60, 49, 51, Optional.of(KICKOFF));
        // Lineup layer made no difference here.
        recordWithBase(service, "KXEPLGAME-26SEP20FULMUN-FUL", 0.30, 0.30, 49, 51, Optional.of(KICKOFF));
        // No base prediction recorded - left out of the comparison.
        recordWithBase(service, "KXEPLGAME-26SEP20MCISUN-MCI", 0.80, null, 49, 51, Optional.of(KICKOFF));
        when(client.fetchSingleMarket("KXEPLGAME-26SEP20BOULFC-LFC")).thenReturn(Optional.of(settled("yes")));
        when(client.fetchSingleMarket("KXEPLGAME-26SEP20FULMUN-FUL")).thenReturn(Optional.of(settled("no")));
        when(client.fetchSingleMarket("KXEPLGAME-26SEP20MCISUN-MCI")).thenReturn(Optional.of(settled("yes")));
        service.checkForResolutions();

        LineupComparison comparison = service.lineupComparison();

        assertEquals(3, comparison.resolvedPredictions());
        assertEquals(2, comparison.comparedPredictions());
        assertEquals(1, comparison.adjustedPredictions());
        assertEquals((0.09 + 0.09) / 2, comparison.withLineupsBrier(), 1e-9);    // (0.7-1)^2, (0.3-0)^2
        assertEquals((0.16 + 0.09) / 2, comparison.withoutLineupsBrier(), 1e-9); // (0.6-1)^2, (0.3-0)^2
    }

    @Test
    void standardErrorTreatsEachMatchAsOneUnit() {
        PredictionLogService service = serviceAt(KICKOFF.minusSeconds(600));
        // Two matches, one market each: per-market Brier differences are
        // (0.7-1)^2-(0.5-1)^2 = -0.16 and (0.3-0)^2-(0.5-0)^2 = -0.16 -> no spread -> SE 0.
        record(service, "KXEPLGAME-26SEP20BOULFC-LFC", 0.70, 49, 51, Optional.of(KICKOFF));
        record(service, "KXEPLGAME-26SEP20FULMUN-FUL", 0.30, 49, 51, Optional.of(KICKOFF));
        when(client.fetchSingleMarket("KXEPLGAME-26SEP20BOULFC-LFC")).thenReturn(Optional.of(settled("yes")));
        when(client.fetchSingleMarket("KXEPLGAME-26SEP20FULMUN-FUL")).thenReturn(Optional.of(settled("no")));
        service.checkForResolutions();

        assertEquals(0.0, service.marketComparison().brierDifferenceStandardError(), 1e-9);
    }
}
