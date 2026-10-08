package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeekendAllocationServiceTest {

    private static final ZoneId EASTERN = ZoneId.of("America/New_York");
    // Thursday Oct 8 2026, noon Eastern.
    private static final ZonedDateTime THURSDAY = ZonedDateTime.of(2026, 10, 8, 12, 0, 0, 0, EASTERN);

    private final WeekendAllocationService service = new WeekendAllocationService(new PoissonModel());

    private MarketEvaluation market(String ticker, int priceCents, String recommendation, double kellyPercent, String kickoffUtc) {
        return new MarketEvaluation(ticker, ticker + " title", priceCents, "40%", priceCents + "%", "5%",
            recommendation, kellyPercent, "understat-live", kickoffUtc);
    }

    @Test
    void weekendRunsThroughTheEndOfTheComingMonday() {
        assertEquals(ZonedDateTime.of(2026, 10, 12, 23, 59, 59, 999_999_999, EASTERN),
            WeekendAllocationService.weekendEnd(THURSDAY));
        ZonedDateTime monday = ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, EASTERN);
        assertEquals(monday.toLocalDate(), WeekendAllocationService.weekendEnd(monday).toLocalDate());
    }

    @Test
    void splitsTheBudgetInProportionToEachPicksKellyStake() {
        WeekendAllocation result = service.allocate(100.0, List.of(
            market("A", 20, "YES (Undervalued)", 3.0, "2026-10-10T14:00:00Z"),
            market("B", 40, "YES (Undervalued)", 1.0, "2026-10-11T13:00:00Z")
        ), THURSDAY);

        assertEquals(2, result.picks().size());
        assertEquals(75.0, result.picks().get(0).sharePercent(), 1e-9);
        assertEquals(75.0, result.picks().get(0).dollars(), 1e-9);
        assertEquals(25.0, result.picks().get(1).dollars(), 1e-9);
        assertEquals(100.0, result.allocatedDollars(), 1e-9);
    }

    @Test
    void onlyBuysWholeContractsIncludingTheFee() {
        // 20c price + 2c Kalshi fee = 22c per contract; $10 buys 45 contracts for $9.90.
        WeekendAllocation result = service.allocate(10.0, List.of(
            market("A", 20, "YES (Undervalued)", 2.0, "2026-10-10T14:00:00Z")
        ), THURSDAY);

        WeekendAllocation.Pick pick = result.picks().get(0);
        assertEquals(2, pick.feeCents());
        assertEquals(45, pick.contracts());
        assertEquals(9.90, pick.costDollars(), 1e-9);
        assertEquals(45.0, pick.payoutDollars(), 1e-9);
        assertEquals(0.10, result.unspentDollars(), 1e-9);
    }

    @Test
    void ignoresNonPicksPastKickoffsAndNextWeekendsGames() {
        WeekendAllocation result = service.allocate(50.0, List.of(
            market("fair", 30, "FAIR VALUE", 1.0, "2026-10-10T14:00:00Z"),
            market("avoid", 30, "AVOID", 0.0, "2026-10-10T14:00:00Z"),
            market("started", 30, "YES (Undervalued)", 2.0, "2026-10-08T14:00:00Z"),      // already kicked off
            market("next-weekend", 30, "YES (Undervalued)", 2.0, "2026-10-17T14:00:00Z"),
            market("no-kickoff", 30, "YES (Undervalued)", 2.0, null),
            market("this-weekend", 30, "YES (Undervalued)", 2.0, "2026-10-12T19:00:00Z") // Monday night game
        ), THURSDAY);

        assertEquals(1, result.picks().size());
        assertEquals("this-weekend", result.picks().get(0).ticker());
    }

    @Test
    void noPicksMeansNothingSpent() {
        WeekendAllocation result = service.allocate(25.0, List.of(), THURSDAY);

        assertTrue(result.picks().isEmpty());
        assertEquals(0.0, result.costDollars(), 1e-9);
        assertEquals(25.0, result.unspentDollars(), 1e-9);
    }
}
