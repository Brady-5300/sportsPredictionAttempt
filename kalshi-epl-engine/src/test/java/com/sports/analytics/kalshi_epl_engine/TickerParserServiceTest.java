package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickerParserServiceTest {

    private final TickerParserService parser = new TickerParserService();

    @Test
    void parsesMatchDateFromRealTickerFormat() {
        Optional<LocalDate> date = parser.parseMatchDateFromTicker("KXEPLGAME-26SEP20FULMUN-FUL");
        assertEquals(Optional.of(LocalDate.of(2026, 9, 20)), date);
    }

    @Test
    void parsesDifferentMonthsCorrectly() {
        assertEquals(Optional.of(LocalDate.of(2025, 1, 5)), parser.parseMatchDateFromTicker("KXEPLGAME-25JAN05ARSMCI-ARS"));
        assertEquals(Optional.of(LocalDate.of(2025, 12, 25)), parser.parseMatchDateFromTicker("KXEPLGAME-25DEC25LIVCHE-T"));
    }

    @Test
    void returnsEmptyForMalformedTicker() {
        assertTrue(parser.parseMatchDateFromTicker("not-a-real-ticker").isEmpty());
    }

    @Test
    void returnsEmptyForNullTicker() {
        assertTrue(parser.parseMatchDateFromTicker(null).isEmpty());
    }

    @Test
    void extractTeamsFromTitleStillWorksUnaffected() {
        var teams = parser.extractTeamsFromTitle("Fulham vs Manchester United: Fulham wins");
        assertEquals("Fulham", teams.getKey());
        assertEquals("Manchester United", teams.getValue());
    }

    @Test
    void stripsWinnerQuestionSuffixFromAwayTeam() {
        var teams = parser.extractTeamsFromTitle("Aston Villa vs Arsenal Winner?");
        assertEquals("Aston Villa", teams.getKey());
        assertEquals("Arsenal", teams.getValue());
    }
}
