package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hits the real "Free API Live Football Data" service. Disabled by default -
 * this API's free tier is capped at 100 requests/MONTH (a hard limit), so
 * don't run this casually; each test here spends real quota.
 *
 * Set API_FOOTBALL_KEY as an environment variable before running.
 */
@Disabled("manual verification only - spends real monthly quota against the live API")
class ApiFootballLiveSmokeTest {

    private final ApiFootballClient client = new ApiFootballClient(
        System.getenv("API_FOOTBALL_KEY"),
        "https://free-api-live-football-data.p.rapidapi.com",
        new RestTemplate()
    );

    @Test
    void resolvesRealArsenalTeamId() {
        Optional<Integer> id = client.resolveTeamId("Arsenal");
        System.out.println("Resolved Arsenal team id: " + id);
        assertTrue(id.isPresent());
    }

    @Test
    void fetchesRealSquadWithInjuryStatus() {
        Optional<Integer> id = client.resolveTeamId("Arsenal");
        assertTrue(id.isPresent());

        List<ApiFootballSquadMember> squad = client.fetchSquad(id.get());
        System.out.println("Squad size: " + squad.size());
        squad.stream().filter(ApiFootballSquadMember::injured)
            .forEach(p -> System.out.println("INJURED: " + p.name() + " (" + p.primaryPosition() + ") - back " + p.expectedReturn()));

        assertTrue(!squad.isEmpty());
    }
}
