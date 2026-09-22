package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Hits the real understat.com endpoints. Disabled by default (no network calls
 * in the regular suite) - run manually to confirm the scraper still matches
 * Understat's live site whenever this integration is suspected to be stale.
 */
@Disabled("manual verification only - hits live understat.com")
class UnderstatScraperLiveSmokeTest {

    private final UnderstatScraperService scraper = new UnderstatScraperService();

    @Test
    void fetchesRealTeamMatches() {
        List<UnderstatTeamMatch> matches = scraper.fetchTeamMatches("Arsenal", 2024);
        assertFalse(matches.isEmpty(), "expected at least one match from live Understat data");
        System.out.println("Fetched " + matches.size() + " matches, first: "
            + matches.get(0).getHomeTeamTitle() + " vs " + matches.get(0).getAwayTeamTitle());
    }

    @Test
    void fetchesRealMatchShots() {
        Map<String, List<UnderstatShot>> shots = scraper.fetchMatchShots("26604");
        assertFalse(shots.get("h").isEmpty(), "expected home shots from live Understat data");
        System.out.println("Home shots: " + shots.get("h").size() + ", Away shots: " + shots.get("a").size());
    }
}
