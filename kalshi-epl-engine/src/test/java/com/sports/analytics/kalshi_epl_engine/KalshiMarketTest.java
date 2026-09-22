package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
