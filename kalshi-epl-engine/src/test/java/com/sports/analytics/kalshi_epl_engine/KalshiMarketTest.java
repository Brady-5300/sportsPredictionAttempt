package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KalshiMarketTest {

    @Test
    void prefersYesBidDollarsOverEverythingElse() {
        KalshiMarket market = new KalshiMarket();
        market.setYesBidDollars("0.62");
        market.setLastPriceDollars("0.40");
        market.setYesBid(10);
        market.setLastPrice(20);

        assertEquals(62, market.resolvePriceCents());
    }

    @Test
    void fallsBackToLastPriceDollarsWhenYesBidDollarsMissing() {
        KalshiMarket market = new KalshiMarket();
        market.setLastPriceDollars("0.37");
        market.setYesBid(10);
        market.setLastPrice(20);

        assertEquals(37, market.resolvePriceCents());
    }

    @Test
    void fallsBackToLegacyYesBidCentsWhenNoDollarFieldsPresent() {
        KalshiMarket market = new KalshiMarket();
        market.setYesBid(55);
        market.setLastPrice(20);

        assertEquals(55, market.resolvePriceCents());
    }

    @Test
    void fallsBackToLegacyLastPriceCentsWhenYesBidIsZero() {
        KalshiMarket market = new KalshiMarket();
        market.setYesBid(0);
        market.setLastPrice(48);

        assertEquals(48, market.resolvePriceCents());
    }

    @Test
    void returnsZeroWhenNoPricingDataAvailable() {
        KalshiMarket market = new KalshiMarket();

        assertEquals(0, market.resolvePriceCents());
    }

    @Test
    void ignoresUnparseableDollarFieldsAndFallsThroughToLegacyFields() {
        KalshiMarket market = new KalshiMarket();
        market.setYesBidDollars("not-a-number");
        market.setYesBid(41);

        assertEquals(41, market.resolvePriceCents());
    }

    @Test
    void estimatesKickoffAsThreeHoursBeforeOccurrenceTime() {
        // Real example: Fulham vs Man Utd kicked off 15:30 UTC; Kalshi's occurrence_datetime was 18:30.
        KalshiMarket market = new KalshiMarket();
        market.setOccurrenceDatetime("2026-09-20T18:30:00Z");

        assertEquals(Optional.of(Instant.parse("2026-09-20T15:30:00Z")), market.estimatedKickoff());
    }

    @Test
    void kickoffIsEmptyWhenOccurrenceTimeMissingOrUnparseable() {
        KalshiMarket market = new KalshiMarket();
        assertTrue(market.estimatedKickoff().isEmpty());

        market.setOccurrenceDatetime("not-a-date");
        assertTrue(market.estimatedKickoff().isEmpty());
    }

    @Test
    void readsBidAndAskInCents() {
        KalshiMarket market = new KalshiMarket();
        market.setYesBidDollars("0.4700");
        market.setYesAskDollars("0.4900");

        assertEquals(Optional.of(47), market.yesBidCents());
        assertEquals(Optional.of(49), market.yesAskCents());
    }
}
