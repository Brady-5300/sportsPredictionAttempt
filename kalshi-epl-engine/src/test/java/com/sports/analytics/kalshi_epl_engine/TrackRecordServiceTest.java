package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrackRecordServiceTest {

    private static final ZoneId EASTERN = ZoneId.of("America/New_York");
    private static final Instant SAT_OCT_10 = Instant.parse("2026-10-10T14:00:00Z");
    private static final Instant SAT_OCT_17 = Instant.parse("2026-10-17T14:00:00Z");

    @TempDir
    Path tempDir;

    private final KalshiHistoricalClient client = mock(KalshiHistoricalClient.class);

    private KalshiMarket settled(String result) {
        KalshiMarket m = new KalshiMarket();
        m.setResult(result);
        return m;
    }

    /** Logs one match's three markets: model probabilities, Kalshi bid/ask, and which outcome happened. */
    private void logMatch(PredictionLogService log, String eventTicker, String home, String away, Instant kickoff,
                          double[] model, int[][] bidAsk, int winnerIndex) {
        String[] suffix = {"H", "T", "A"};
        String[] type = {"HOME", "TIE", "AWAY"};
        String[] label = {home + " wins", "Tie is the result", away + " wins"};
        log.nowSupplier = () -> kickoff.minusSeconds(600);
        for (int i = 0; i < 3; i++) {
            String ticker = eventTicker + "-" + suffix[i];
            log.recordSnapshot(ticker, home + " vs " + away + ": " + label[i], type[i], kickoff.toString().substring(0, 10),
                model[i], model[i], bidAsk[i][0], bidAsk[i][1], Optional.of(kickoff), false);
            when(client.fetchSingleMarket(ticker)).thenReturn(Optional.of(settled(i == winnerIndex ? "yes" : "no")));
        }
    }

    private TrackRecord build() {
        PredictionLogService log = new PredictionLogService(client, tempDir.resolve("log.jsonl"));
        // Week 1: model rates Leeds (away) highly and Leeds win - model beats Kalshi.
        logMatch(log, "KXEPLGAME-26OCT10ARSLEE", "Arsenal", "Leeds United", SAT_OCT_10,
            new double[]{0.30, 0.25, 0.45}, new int[][]{{69, 71}, {17, 19}, {11, 13}}, 2);
        // Week 2: model fancies the draw, home side wins - Kalshi beats the model.
        logMatch(log, "KXEPLGAME-26OCT17FULHUL", "Fulham", "Hull City", SAT_OCT_17,
            new double[]{0.40, 0.35, 0.25}, new int[][]{{59, 61}, {23, 25}, {19, 21}}, 0);
        log.nowSupplier = () -> SAT_OCT_17.plusSeconds(4 * 3600);
        log.checkForResolutions();
        return new TrackRecordService(log, new PoissonModel()).build(EASTERN);
    }

    @Test
    void listsFinishedMatchesNewestFirstWithWhatHappened() {
        TrackRecord record = build();

        assertEquals(2, record.finishedMatches());
        assertEquals("Fulham vs Hull City", record.matches().get(0).name());
        assertEquals("Fulham wins", record.matches().get(0).result());
        assertEquals("Leeds United wins", record.matches().get(1).result());
        assertEquals(3, record.matches().get(1).markets().size());
    }

    @Test
    void scoresEachMatchAndWeekendModelAgainstKalshi() {
        TrackRecord record = build();

        assertEquals("kalshi", record.matches().get(0).winner());
        assertEquals("model", record.matches().get(1).winner());
        assertEquals(2, record.weeks().size());
        assertEquals("2026-10-19", record.weeks().get(0).weekendEnding()); // Monday after Oct 17
        assertEquals("2026-10-12", record.weeks().get(1).weekendEnding());
        assertEquals(1, record.modelWeeks());
        assertEquals(1, record.kalshiWeeks());
        assertEquals(6, record.overall().comparedPredictions());
    }

    @Test
    void countsHowTheYesPicksWouldHaveDoneAtOneDollarEach() {
        TrackRecord record = build();

        // Picks: model minus (ask + fee) above 3 points.
        // Leeds: 0.45 - (13 + 1)/100 = +0.31 (won). Arsenal-Leeds tie: 0.25 - 0.21 = +0.04 (lost).
        // Fulham-Hull tie: 0.35 - 0.27 = +0.08 (lost). Hull: 0.25 - 0.23 = +0.02 (not a pick).
        TrackRecord.PickRecord picks = record.picks();
        assertEquals(3, picks.picks());
        assertEquals(1, picks.wins());
        assertEquals(3.0, picks.staked(), 1e-9);
        assertEquals(Math.round(100.0 / 14 * 100) / 100.0, picks.returned(), 1e-9);
        assertTrue(record.matches().get(1).markets().get(2).pick());
        assertFalse(record.matches().get(0).markets().get(2).pick());
    }

    @Test
    void namesMarketsFromWinnerStyleTitlesToo() {
        PredictionLogEntry e = new PredictionLogEntry("KXEPLGAME-26AUG30AVLARS-ARS", "Aston Villa vs Arsenal Winner?", "AWAY",
            "2026-08-30", 0.4, 0.4, 40, 42, null, null, null, true, true, null, null, null, null, null);

        assertEquals("Aston Villa vs Arsenal", TrackRecordService.matchName(e));
        assertEquals("Arsenal wins", TrackRecordService.outcomeLabel(e));
    }

    @Test
    void emptyLogGivesAnEmptyRecord() {
        TrackRecord record = new TrackRecordService(new PredictionLogService(client, tempDir.resolve("empty.jsonl")), new PoissonModel()).build(EASTERN);

        assertEquals(0, record.finishedMatches());
        assertTrue(record.weeks().isEmpty());
        assertEquals(0, record.picks().picks());
    }
}
