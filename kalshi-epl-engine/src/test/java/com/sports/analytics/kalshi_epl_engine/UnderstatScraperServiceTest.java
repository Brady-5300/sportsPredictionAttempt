package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnderstatScraperServiceTest {

    private final UnderstatScraperService scraper = new UnderstatScraperService();

    // Trimmed real payload shape from GET /getTeamData/Arsenal/2024 (captured 2026-09-22).
    private static final String TEAM_DATA_JSON = """
        {"dates":[
          {"id":"26604","isResult":true,"side":"h",
           "h":{"id":"83","title":"Arsenal","short_title":"ARS"},
           "a":{"id":"229","title":"Wolverhampton Wanderers","short_title":"WOL"},
           "goals":{"h":"2","a":"0"},"xG":{"h":"1.6283","a":"0.575835"},
           "datetime":"2024-08-17 14:00:00","result":"w"},
          {"id":"26618","isResult":true,"side":"a",
           "h":{"id":"71","title":"Aston Villa","short_title":"AVL"},
           "a":{"id":"83","title":"Arsenal","short_title":"ARS"},
           "goals":{"h":"0","a":"2"},"xG":{"h":"1.31664","a":"1.41399"},
           "datetime":"2024-08-24 16:30:00","result":"w"},
          {"id":"26900","isResult":false,"side":"h",
           "h":{"id":"83","title":"Arsenal","short_title":"ARS"},
           "a":{"id":"88","title":"Manchester City","short_title":"MCI"},
           "goals":{"h":null,"a":null},"xG":{"h":"0","a":"0"},
           "datetime":"2025-05-01 15:00:00","result":null}
        ]}
        """;

    // Trimmed real payload shape from GET /getMatchData/26604 (captured 2026-09-22).
    private static final String MATCH_DATA_JSON = """
        {"rosters":{},"tmpl":"",
         "shots":{
           "h":[
             {"id":"584738","minute":"13","result":"SavedShot","X":"0.735","Y":"0.435",
              "xG":"0.01839972287416458","player":"Ben White","h_a":"h","situation":"OpenPlay",
              "shotType":"RightFoot","match_id":"26604"}
           ],
           "a":[
             {"id":"584737","minute":"5","result":"BlockedShot","X":"0.865","Y":"0.406",
              "xG":"0.06888187676668167","player":"Matt Doherty","h_a":"a","situation":"OpenPlay",
              "shotType":"RightFoot","match_id":"26604"}
           ]
         }}
        """;

    @Test
    void parsesTeamMatchesFromRealPayloadShape() {
        List<UnderstatTeamMatch> matches = scraper.parseTeamMatches(TEAM_DATA_JSON);

        assertEquals(3, matches.size());
        assertEquals("26604", matches.get(0).getId());
        assertTrue(matches.get(0).isResult());
        assertEquals("Arsenal", matches.get(0).getHomeTeamTitle());
        assertEquals("Wolverhampton Wanderers", matches.get(0).getAwayTeamTitle());

        assertEquals("26900", matches.get(2).getId());
        assertTrue(!matches.get(2).isResult(), "fixture not yet played should have isResult=false");
    }

    @Test
    void returnsEmptyListWhenDatesFieldMissing() {
        List<UnderstatTeamMatch> matches = scraper.parseTeamMatches("{\"somethingElse\":[]}");
        assertTrue(matches.isEmpty());
    }

    @Test
    void returnsEmptyListForNullInput() {
        assertTrue(scraper.parseTeamMatches(null).isEmpty());
    }

    @Test
    void parsesMatchShotsFromRealPayloadShape() {
        Map<String, List<UnderstatShot>> shots = scraper.parseMatchShots(MATCH_DATA_JSON);

        assertEquals(1, shots.get("h").size());
        assertEquals(1, shots.get("a").size());

        UnderstatShot homeShot = shots.get("h").get(0);
        assertEquals("Ben White", homeShot.getPlayer());
        assertEquals("OpenPlay", homeShot.getSituation());
        assertEquals("RightFoot", homeShot.getShotType());
        assertEquals(0.735, homeShot.parsedX(), 0.0001);
        assertEquals(0.435, homeShot.parsedY(), 0.0001);
    }

    @Test
    void returnsEmptyShotsWhenShotsFieldMissing() {
        Map<String, List<UnderstatShot>> shots = scraper.parseMatchShots("{\"rosters\":{}}");
        assertTrue(shots.get("h").isEmpty());
        assertTrue(shots.get("a").isEmpty());
    }

    @Test
    void returnsEmptyShotsForNullInput() {
        Map<String, List<UnderstatShot>> shots = scraper.parseMatchShots(null);
        assertTrue(shots.get("h").isEmpty());
        assertTrue(shots.get("a").isEmpty());
    }
}
