package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests JSON parsing against real captured response shapes from
 * free-api-live-football-data.p.rapidapi.com (captured 2026-09-22 with a live key).
 */
class ApiFootballClientTest {

    private final ApiFootballClient client =
        new ApiFootballClient("test-key", "https://free-api-live-football-data.p.rapidapi.com", new RestTemplate());

    // Trimmed real response from GET /football-teams-search?search=Arsenal
    private static final String TEAM_SEARCH_JSON = """
        {"status":"success","response":{"suggestions":[
          {"type":"team","id":"9825","score":300990,"name":"Arsenal","leagueId":47,"leagueName":"Premier League"},
          {"type":"team","id":"258657","score":300041,"name":"Arsenal (W)","leagueId":9227,"leagueName":"WSL"},
          {"type":"team","id":"10098","score":300001,"name":"Arsenal Sarandi","leagueId":9213,"leagueName":"Primera B Metropolitana"}
        ]}}
        """;

    // Trimmed real response from GET /football-get-list-player?teamid=9825
    private static final String SQUAD_JSON = """
        {"status":"success","response":{"list":{"squad":[
          {"title":"coach","members":[
            {"id":24011,"name":"Mikel Arteta","role":{"key":"coach","fallback":"Coach"}}
          ]},
          {"title":"keepers","members":[
            {"id":562727,"name":"David Raya","injury":null,"positionIds":"11","positionIdsDesc":"GK"}
          ]},
          {"title":"defenders","members":[
            {"id":955406,"name":"William Saliba","injured":true,"injury":{"id":"45","expectedReturn":"Mid October 2026"},"positionIds":"34","positionIdsDesc":"CB"}
          ]},
          {"title":"attackers","members":[
            {"id":7322,"name":"Bukayo Saka","injury":null,"positionIds":"83,85","positionIdsDesc":"RW,CAM"}
          ]}
        ]},"isNationalTeam":false}}
        """;

    @Test
    void resolvesTeamPreferringPremierLeagueOverSameNamedClubsElsewhere() {
        Optional<Integer> id = client.parseTeamSearch(TEAM_SEARCH_JSON);
        assertTrue(id.isPresent());
        assertEquals(9825, id.get());
    }

    @Test
    void returnsEmptyWhenTeamSearchHasNoResults() {
        Optional<Integer> id = client.parseTeamSearch("{\"response\":{\"suggestions\":[]}}");
        assertTrue(id.isEmpty());
    }

    @Test
    void parsesSquadFlatteningAllPositionGroups() {
        List<ApiFootballSquadMember> squad = client.parseSquad(SQUAD_JSON);

        // coach is excluded (no "name" issue, but it's not a player) - actually coach has a name,
        // so it IS included since we don't filter by role; verify count includes all 4 members.
        assertEquals(4, squad.size());
    }

    @Test
    void marksInjuredPlayerCorrectlyWithExpectedReturn() {
        List<ApiFootballSquadMember> squad = client.parseSquad(SQUAD_JSON);

        ApiFootballSquadMember saliba = squad.stream()
            .filter(p -> p.name().equals("William Saliba"))
            .findFirst().orElseThrow();

        assertTrue(saliba.injured());
        assertEquals("Mid October 2026", saliba.expectedReturn());
        assertEquals("CB", saliba.primaryPosition());
        assertTrue(saliba.isDefensivePosition());
    }

    @Test
    void healthyPlayersAreNotMarkedInjured() {
        List<ApiFootballSquadMember> squad = client.parseSquad(SQUAD_JSON);

        ApiFootballSquadMember raya = squad.stream()
            .filter(p -> p.name().equals("David Raya"))
            .findFirst().orElseThrow();

        assertFalse(raya.injured());
    }

    @Test
    void takesFirstListedPositionWhenPlayerHasMultiplePositions() {
        List<ApiFootballSquadMember> squad = client.parseSquad(SQUAD_JSON);

        ApiFootballSquadMember saka = squad.stream()
            .filter(p -> p.name().equals("Bukayo Saka"))
            .findFirst().orElseThrow();

        assertEquals("RW", saka.primaryPosition());
        assertFalse(saka.isDefensivePosition());
    }

    @Test
    void returnsEmptySquadWhenResponseFieldMissing() {
        assertTrue(client.parseSquad("{}").isEmpty());
    }

    @Test
    void returnsEmptyEverythingWhenApiKeyIsBlank() {
        ApiFootballClient noKeyClient =
            new ApiFootballClient("", "https://free-api-live-football-data.p.rapidapi.com", new RestTemplate());

        assertTrue(noKeyClient.resolveTeamId("Arsenal").isEmpty());
        assertTrue(noKeyClient.fetchSquad(9825).isEmpty());
    }
}
