package com.sports.analytics.kalshi_epl_engine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for FotMob's own public JSON API (www.fotmob.com/api/data/...) - no
 * API key, no publisher-enforced request cap, curl-friendly (unlike Sofascore,
 * which blocks non-browser clients at the network edge - confirmed by testing
 * before building this). This replaces the earlier RapidAPI-hosted
 * "Free API Live Football Data" client: that service turned out to be
 * reselling this exact same underlying FotMob data (identical JSON shapes)
 * behind a paid key and a 100-requests/MONTH hard cap - going directly to the
 * source removes that ceiling entirely.
 *
 * Still a scrape of an undocumented endpoint, not a sanctioned API - every
 * method fails gracefully (empty result) rather than throwing.
 *
 * Endpoints verified against live responses on 2026-09-22.
 */
@Service
public class FotMobClient {

    /** Source name used with {@link ScraperHealthMonitor}. */
    public static final String SOURCE = "fotmob";

    // Both this API and the old RapidAPI reseller use this numbering; multiple
    // countries have a competition literally named "Premier League" (e.g. Ghana's
    // is a different id), so team/league resolution must match on this id, not the name.
    private static final int ENGLISH_PREMIER_LEAGUE_ID = 47;

    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final ScraperHealthMonitor healthMonitor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Optional<Integer>> teamIdCache = new ConcurrentHashMap<>();

    @Autowired
    public FotMobClient(@Value("${fotmob.base-url}") String baseUrl, ScraperHealthMonitor healthMonitor) {
        this.baseUrl = baseUrl;
        this.healthMonitor = healthMonitor;
        this.restTemplate = new RestTemplate();
    }

    // Test-only constructor: inject a mock RestTemplate, no health monitor.
    FotMobClient(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
        this.healthMonitor = new ScraperHealthMonitor();
    }

    /**
     * Resolves an EPL team's name to FotMob's internal numeric team id. Cached
     * indefinitely, but only on a successful fetch - a transient failure isn't
     * cached as "no such team", so it's retried on the next call.
     */
    public Optional<Integer> resolveTeamId(String teamName) {
        String key = teamName.toLowerCase(Locale.ROOT);
        Optional<Integer> cached = teamIdCache.get(key);
        if (cached != null) return cached;

        try {
            String json = get("/api/data/search/suggest?hits=50&lang=en&term=" + URLEncoder.encode(teamName, StandardCharsets.UTF_8));
            Optional<Integer> result = parseTeamSearch(json);
            healthMonitor.recordSuccess(SOURCE);
            teamIdCache.put(key, result);
            return result;
        } catch (Exception e) {
            System.err.println("[FOTMOB] Failed to resolve team id for " + teamName + ": " + e.getMessage());
            healthMonitor.recordFailure(SOURCE, e.getMessage());
            return Optional.empty();
        }
    }

    /** This team's full fixture list (past and upcoming, all competitions). */
    public List<FotMobFixture> fetchFixtures(int teamId) {
        try {
            String json = get("/api/data/teams?id=" + teamId);
            List<FotMobFixture> result = parseFixtures(json);
            healthMonitor.recordSuccess(SOURCE);
            return result;
        } catch (Exception e) {
            System.err.println("[FOTMOB] Failed to fetch fixtures for team " + teamId + ": " + e.getMessage());
            healthMonitor.recordFailure(SOURCE, e.getMessage());
            return List.of();
        }
    }

    /** Both sides' confirmed lineup (if posted) and currently-unavailable players for one match. */
    public FotMobMatchLineups fetchMatchLineups(int matchId) {
        try {
            String json = get("/api/data/matchDetails?matchId=" + matchId);
            FotMobMatchLineups result = parseMatchLineups(json);
            healthMonitor.recordSuccess(SOURCE);
            return result;
        } catch (Exception e) {
            System.err.println("[FOTMOB] Failed to fetch lineups for match " + matchId + ": " + e.getMessage());
            healthMonitor.recordFailure(SOURCE, e.getMessage());
            return FotMobMatchLineups.empty();
        }
    }

    Optional<Integer> parseTeamSearch(String json) {
        if (json == null) return Optional.empty();
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) return Optional.empty();

        for (JsonNode group : root) {
            JsonNode suggestions = group.path("suggestions");
            if (!suggestions.isArray()) continue;

            for (JsonNode item : suggestions) {
                if (!"team".equals(item.path("type").asString(""))) continue;
                if (item.path("leagueId").asInt(-1) == ENGLISH_PREMIER_LEAGUE_ID) {
                    return idOf(item);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Integer> idOf(JsonNode node) {
        JsonNode id = node.get("id");
        if (id == null) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(id.asString()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    List<FotMobFixture> parseFixtures(String json) {
        if (json == null) return List.of();
        JsonNode root = objectMapper.readTree(json);
        JsonNode fixtures = root.path("fixtures").path("allFixtures").path("fixtures");
        if (!fixtures.isArray()) return List.of();

        List<FotMobFixture> result = new ArrayList<>();
        for (JsonNode fixture : fixtures) {
            JsonNode idNode = fixture.get("id");
            String utcTime = fixture.path("status").path("utcTime").asString(null);
            if (idNode == null || utcTime == null) continue;

            try {
                LocalDate date = Instant.parse(utcTime).atZone(ZoneOffset.UTC).toLocalDate();
                int leagueId = fixture.path("tournament").path("leagueId").asInt(-1);
                boolean finished = fixture.path("status").path("finished").asBoolean(false);
                result.add(new FotMobFixture(idNode.asInt(), leagueId, date, finished));
            } catch (Exception ignored) {
                // skip a malformed entry rather than failing the whole list
            }
        }
        return result;
    }

    FotMobMatchLineups parseMatchLineups(String json) {
        if (json == null) return FotMobMatchLineups.empty();
        JsonNode root = objectMapper.readTree(json);
        JsonNode lineup = root.path("content").path("lineup");
        if (lineup.isMissingNode()) return FotMobMatchLineups.empty();

        JsonNode home = lineup.path("homeTeam");
        JsonNode away = lineup.path("awayTeam");

        return new FotMobMatchLineups(
            parseStarterNames(home),
            parseUnavailable(home),
            parseStarterNames(away),
            parseUnavailable(away)
        );
    }

    private List<String> parseStarterNames(JsonNode teamNode) {
        List<String> names = new ArrayList<>();
        JsonNode starters = teamNode.path("starters");
        if (!starters.isArray()) return names;
        for (JsonNode player : starters) {
            String name = player.path("name").asString(null);
            if (name != null) names.add(name);
        }
        return names;
    }

    private List<FotMobUnavailablePlayer> parseUnavailable(JsonNode teamNode) {
        List<FotMobUnavailablePlayer> result = new ArrayList<>();
        JsonNode unavailable = teamNode.path("unavailable");
        if (!unavailable.isArray()) return result;

        for (JsonNode player : unavailable) {
            String name = player.path("name").asString(null);
            if (name == null) continue;
            String type = player.path("unavailability").path("type").asString("");
            String expectedReturn = player.path("unavailability").path("expectedReturn").asString("");
            result.add(new FotMobUnavailablePlayer(name, type, expectedReturn));
        }
        return result;
    }

    /**
     * FotMob's JSON responses declare charset=utf-8 explicitly (unlike the old
     * RapidAPI reseller, which omitted it and caused mangled accented names) -
     * still fetch as bytes and decode explicitly rather than relying on that
     * being respected correctly everywhere.
     */
    private String get(String path) {
        URI uri = URI.create(baseUrl + path);
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (compatible; kalshi-epl-engine/1.0)");
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        byte[] body = restTemplate.exchange(uri, HttpMethod.GET, entity, byte[].class).getBody();
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }
}
