package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the full live-data chain (scraper -> ShotXgCalculator -> provider ->
 * XgService) against the real understat.com site. Disabled by default; run
 * manually to confirm the end-to-end wiring still works.
 */
@Disabled("manual verification only - hits live understat.com")
class XgServiceLiveSmokeTest {

    private final TeamNameResolver resolver = new TeamNameResolver();
    private final UnderstatScraperService scraper = new UnderstatScraperService();
    private final ShotXgCalculator calculator = new ShotXgCalculator();
    private final UnderstatXgProvider provider = new UnderstatXgProvider(scraper, calculator, resolver);
    private final FotMobClient fotMobClient =
        new FotMobClient("https://www.fotmob.com", new org.springframework.web.client.RestTemplate());
    private final LineupFormAdjustmentService lineupFormAdjustmentService =
        new LineupFormAdjustmentService(fotMobClient, provider);
    private final XgService xgService = new XgService(resolver, provider, lineupFormAdjustmentService);

    @Test
    void computesLiveXgForARealFixture() {
        double homeXg = xgService.calculateHomeXG("Arsenal", "Fulham");
        double awayXg = xgService.calculateAwayXG("Arsenal", "Fulham");
        boolean live = xgService.hasLiveDataFor("Arsenal", "Fulham");

        System.out.println("Arsenal (home) xG: " + homeXg + ", Fulham (away) xG: " + awayXg + ", live=" + live);

        assertTrue(live, "expected live Understat data for two known current EPL teams");
        assertTrue(homeXg > 0 && homeXg < 5, "home xG (" + homeXg + ") should be a plausible match xG value");
        assertTrue(awayXg > 0 && awayXg < 5, "away xG (" + awayXg + ") should be a plausible match xG value");
    }
}
