package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PoissonModelTest {

    private final PoissonModel model = new PoissonModel();

    @Test
    void feeIsZeroAtExtremePrices() {
        assertEquals(0, model.calculateKalshiFeeCents(0));
        assertEquals(0, model.calculateKalshiFeeCents(100));
        assertEquals(0, model.calculateKalshiFeeCents(-5));
        assertEquals(0, model.calculateKalshiFeeCents(150));
    }

    @Test
    void feePeaksNearFiftyCents() {
        int feeAt50 = model.calculateKalshiFeeCents(50);
        int feeAt10 = model.calculateKalshiFeeCents(10);
        int feeAt90 = model.calculateKalshiFeeCents(90);

        assertEquals(2, feeAt50); // ceil(0.07 * 0.5 * 0.5 * 100) = ceil(1.75) = 2
        assertEquals(1, feeAt10); // ceil(0.07 * 0.1 * 0.9 * 100) = ceil(0.63) = 1
        assertEquals(feeAt10, feeAt90); // fee is symmetric around 50c
    }

    @Test
    void feeIsRoundedUpNotDown() {
        // ceil(0.07 * 0.01 * 0.99 * 100) = ceil(0.0693) = 1, not truncated to 0
        assertEquals(1, model.calculateKalshiFeeCents(1));
    }
}
