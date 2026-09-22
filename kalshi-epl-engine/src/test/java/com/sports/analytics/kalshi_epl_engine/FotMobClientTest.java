package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests JSON parsing against real captured response shapes from
 * www.fotmob.com/api/data/... (captured 2026-09-22 with live requests).
 */
class FotMobClientTest {

    private final FotMobClient client = new FotMobClient("https://www.fotmob.com", new RestTemplate());

    // Trimmed real response from GET /api/data/search/suggest?term=Arsenal
    private static final String TEAM_SEARCH_JSON = """
        [{"title":{"key":"all","value":"All"},"suggestions":[
          {"type":"team","id":"9825","score":300990,"name":"Arsenal","leagueId":47,"leagueName":"Premier League"},
          {"type":"team","id":"258657","score":300041,"name":"Arsenal (W)","leagueId":9227,"leagueName":"WSL"},
          {"type":"team","id":"1142489","score":300000,"name":"Arsenal Dzerzhinsk","leagueId":263,"leagueName":"Premier League"},
          {"type":"match","id":"5795457","score":0,"leagueId":47,"leagueName":"Premier League"}
        ]}]
        """;

    // Trimmed real response from GET /api/data/teams?id=9825
    private static final String FIXTURES_JSON = """
        {"fixtures":{"allFixtures":{"fixtures":[
          {"id":5795459,"tournament":{"name":"Premier League","leagueId":47},
           "status":{"utcTime":"2026-09-20T16:30:00.000Z","finished":true,"started":true}},
          {"id":6258013,"tournament":{"name":"Azadegan League","leagueId":9372},
           "status":{"utcTime":"2026-09-27T13:30:00.000Z","finished":false,"started":false}},
          {"id":5975038,"tournament":{"name":"Premier League","leagueId":522},
           "status":{"utcTime":"2026-09-27T15:30:00.000Z","finished":false,"started":false}}
        ]}}}
        """;

    // Trimmed real response from GET /api/data/matchDetails?matchId=5795459 (finished match)
    private static final String CONFIRMED_LINEUP_JSON = """
        {"content":{"lineup":{"matchId":5795459,"lineupType":"standard",
          "homeTeam":{"id":9879,"name":"Fulham","starters":[
            {"id":215168,"name":"Bernd Leno","positionId":11}
          ],"unavailable":[]},
          "awayTeam":{"id":9825,"name":"Manchester United","starters":[
            {"id":1178602,"name":"Senne Lammens","positionId":11}
          ],"unavailable":[]}
        }}}
        """;

    // Trimmed real response for an upcoming match with injuries known but no lineup posted yet
    private static final String UNCONFIRMED_LINEUP_JSON = """
        {"content":{"lineup":{"matchId":16363871,"lineupType":"standard",
          "homeTeam":{"id":9825,"name":"Arsenal","unavailable":[
            {"id":941168,"name":"William Saliba","unavailability":{"injuryId":45,"type":"injury","expectedReturn":"Mid October 2026"}}
          ]},
          "awayTeam":{"id":8463,"name":"Leeds United","unavailable":[
            {"id":1170693,"name":"Mateo Joseph","unavailability":{"injuryId":22,"type":"injury","expectedReturn":"2027-01-10"}}
          ]}
        }}}
        """;

    @Test
    void resolvesTeamPreferringEnglishPremierLeagueOverSameNamedLeagueElsewhere() {
        Optional<Integer> id = client.parseTeamSearch(TEAM_SEARCH_JSON);
        assertTrue(id.isPresent());
        assertEquals(9825, id.get());
    }

    @Test
    void ignoresNonTeamSuggestions() {
        // The "match" suggestion in the fixture has leagueId 47 too - must not be picked up as a team.
        Optional<Integer> id = client.parseTeamSearch(TEAM_SEARCH_JSON);
        assertEquals(9825, id.get()); // not 5795457
    }

    @Test
    void returnsEmptyWhenTeamSearchHasNoMatches() {
        assertTrue(client.parseTeamSearch("[{\"suggestions\":[]}]").isEmpty());
    }

    @Test
    void parsesFixturesFilteringByLeagueId() {
        List<FotMobFixture> fixtures = client.parseFixtures(FIXTURES_JSON);

        assertEquals(3, fixtures.size());
        FotMobFixture eplFixture = fixtures.stream().filter(f -> f.leagueId() == 47).findFirst().orElseThrow();
        assertEquals(5795459, eplFixture.matchId());
        assertEquals(LocalDate.of(2026, 9, 20), eplFixture.matchDate());
        assertTrue(eplFixture.finished());
    }

    @Test
    void distinguishesGhanaPremierLeagueFromEnglishPremierLeagueById() {
        List<FotMobFixture> fixtures = client.parseFixtures(FIXTURES_JSON);
        FotMobFixture ghanaFixture = fixtures.stream().filter(f -> f.matchId() == 5975038).findFirst().orElseThrow();
        assertEquals(522, ghanaFixture.leagueId());
        assertFalse(ghanaFixture.leagueId() == 47);
    }

    @Test
    void returnsEmptyFixturesWhenFieldMissing() {
        assertTrue(client.parseFixtures("{}").isEmpty());
    }

    @Test
    void parsesConfirmedStartersForBothSides() {
        FotMobMatchLineups lineups = client.parseMatchLineups(CONFIRMED_LINEUP_JSON);

        assertEquals(List.of("Bernd Leno"), lineups.homeStarters());
        assertEquals(List.of("Senne Lammens"), lineups.awayStarters());
        assertTrue(lineups.homeUnavailable().isEmpty());
    }

    @Test
    void parsesUnavailablePlayersWhenLineupNotYetConfirmed() {
        FotMobMatchLineups lineups = client.parseMatchLineups(UNCONFIRMED_LINEUP_JSON);

        assertTrue(lineups.homeStarters().isEmpty());
        assertEquals(1, lineups.homeUnavailable().size());
        FotMobUnavailablePlayer saliba = lineups.homeUnavailable().get(0);
        assertEquals("William Saliba", saliba.name());
        assertEquals("injury", saliba.type());
        assertEquals("Mid October 2026", saliba.expectedReturn());

        assertEquals("Mateo Joseph", lineups.awayUnavailable().get(0).name());
    }

    @Test
    void returnsEmptyLineupsWhenContentFieldMissing() {
        FotMobMatchLineups lineups = client.parseMatchLineups("{}");
        assertTrue(lineups.homeStarters().isEmpty());
        assertTrue(lineups.homeUnavailable().isEmpty());
        assertTrue(lineups.awayStarters().isEmpty());
        assertTrue(lineups.awayUnavailable().isEmpty());
    }

    @Test
    void returnsEmptyForNullJsonEverywhere() {
        assertTrue(client.parseTeamSearch(null).isEmpty());
        assertTrue(client.parseFixtures(null).isEmpty());
        FotMobMatchLineups lineups = client.parseMatchLineups(null);
        assertTrue(lineups.homeStarters().isEmpty());
    }
}
