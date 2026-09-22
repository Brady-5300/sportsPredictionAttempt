package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hits the real FotMob site. Disabled by default (no network calls in the
 * regular suite) - run manually to confirm the integration still matches
 * reality whenever it seems to have gone stale.
 */
@Disabled("manual verification only - hits live fotmob.com")
class FotMobLiveSmokeTest {

    private final FotMobClient client = new FotMobClient("https://www.fotmob.com", new RestTemplate());

    @Test
    void resolvesRealArsenalTeamId() {
        Optional<Integer> id = client.resolveTeamId("Arsenal");
        System.out.println("Resolved Arsenal team id: " + id);
        assertTrue(id.isPresent());
    }

    @Test
    void fetchesRealFixturesIncludingFinishedPremierLeagueMatches() {
        Optional<Integer> id = client.resolveTeamId("Arsenal");
        assertTrue(id.isPresent());

        List<FotMobFixture> fixtures = client.fetchFixtures(id.get());
        System.out.println("Fixture count: " + fixtures.size());

        boolean hasFinishedEplMatch = fixtures.stream().anyMatch(f -> f.leagueId() == 47 && f.finished());
        assertTrue(hasFinishedEplMatch, "expected at least one finished Premier League fixture in Arsenal's schedule");
    }

    @Test
    void fetchesRealLineupsAndInjuriesForAnUpcomingArsenalFixture() {
        Optional<Integer> id = client.resolveTeamId("Arsenal");
        assertTrue(id.isPresent());

        List<FotMobFixture> fixtures = client.fetchFixtures(id.get());
        FotMobFixture nextEpl = fixtures.stream()
            .filter(f -> f.leagueId() == 47 && !f.finished())
            .min((a, b) -> a.matchDate().compareTo(b.matchDate()))
            .orElseThrow();

        System.out.println("Next EPL fixture: matchId=" + nextEpl.matchId() + " date=" + nextEpl.matchDate());

        FotMobMatchLineups lineups = client.fetchMatchLineups(nextEpl.matchId());
        System.out.println("Home starters: " + lineups.homeStarters());
        System.out.println("Home unavailable: " + lineups.homeUnavailable());
        System.out.println("Away unavailable: " + lineups.awayUnavailable());

        // Far from kickoff, starters will likely be empty but injuries should already be known.
        assertTrue(!lineups.homeUnavailable().isEmpty() || !lineups.awayUnavailable().isEmpty()
                || !lineups.homeStarters().isEmpty(),
            "expected at least some signal (injuries or lineup) for the next fixture");
    }
}
