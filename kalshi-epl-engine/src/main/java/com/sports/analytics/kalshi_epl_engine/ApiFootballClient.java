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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for "Free API Live Football Data" (Creativesdev, via RapidAPI) at
 * free-api-live-football-data.p.rapidapi.com. Needs a RapidAPI key set via
 * the API_FOOTBALL_KEY environment variable - see application.properties.
 * Without a key, every method returns an empty result.
 *
 * IMPORTANT: this is a different API than the official API-Sports
 * "API-Football" product - it has no separate injuries endpoint; injury
 * status is bundled directly into the squad listing instead. Its free tier
 * is capped at 100 requests/MONTH (a hard limit), which is why callers of
 * this client (see LineupFormAdjustmentService) cache aggressively.
 *
 * Endpoints verified against a live response on 2026-09-22.
 */
@Service
public class ApiFootballClient {

    private final String apiKey;
    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Optional<Integer>> teamIdCache = new ConcurrentHashMap<>();

    @Autowired
    public ApiFootballClient(@Value("${apifootball.api-key}") String apiKey,
                              @Value("${apifootball.base-url}") String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.restTemplate = new RestTemplate();
    }

    // Test-only constructor: inject a mock RestTemplate and skip real config binding.
    ApiFootballClient(String apiKey, String baseUrl, RestTemplate restTemplate) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.restTemplate = restTemplate;
    }

    private boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Resolves an EPL team's name to this API's internal numeric team id. Cached indefinitely (team ids don't change). */
    public Optional<Integer> resolveTeamId(String teamName) {
        if (!hasApiKey()) return Optional.empty();

        return teamIdCache.computeIfAbsent(teamName.toLowerCase(Locale.ROOT), key -> {
            try {
                String json = get("/football-teams-search?search=" + URLEncoder.encode(teamName, StandardCharsets.UTF_8));
                return parseTeamSearch(json);
            } catch (Exception e) {
                System.err.println("[API-FOOTBALL] Failed to resolve team id for " + teamName + ": " + e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** Full squad with each player's current injury status. */
    public List<ApiFootballSquadMember> fetchSquad(int teamId) {
        if (!hasApiKey()) return List.of();

        try {
            String json = get("/football-get-list-player?teamid=" + teamId);
            return parseSquad(json);
        } catch (Exception e) {
            System.err.println("[API-FOOTBALL] Failed to fetch squad for team " + teamId + ": " + e.getMessage());
            return List.of();
        }
    }

    Optional<Integer> parseTeamSearch(String json) {
        if (json == null) return Optional.empty();
        JsonNode root = objectMapper.readTree(json);
        JsonNode suggestions = root.path("response").path("suggestions");
        if (!suggestions.isArray() || suggestions.isEmpty()) return Optional.empty();

        // Suggestions are pre-sorted by relevance score, but prefer an explicit
        // "Premier League" match to avoid a same-named club in a different competition/country.
        for (JsonNode item : suggestions) {
            if ("Premier League".equalsIgnoreCase(item.path("leagueName").asString(""))) {
                return idOf(item);
            }
        }
        return idOf(suggestions.get(0));
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

    List<ApiFootballSquadMember> parseSquad(String json) {
        if (json == null) return List.of();
        JsonNode root = objectMapper.readTree(json);
        JsonNode groups = root.path("response").path("list").path("squad");
        if (!groups.isArray()) return List.of();

        List<ApiFootballSquadMember> result = new ArrayList<>();
        for (JsonNode group : groups) {
            JsonNode members = group.path("members");
            if (!members.isArray()) continue;
            for (JsonNode member : members) {
                String name = member.path("name").asString(null);
                if (name == null) continue;

                String positions = member.path("positionIdsDesc").asString("");
                String primaryPosition = positions.isBlank() ? null : positions.split(",")[0].trim();

                boolean injured = member.path("injured").asBoolean(false);
                String expectedReturn = member.path("injury").path("expectedReturn").asString(null);

                result.add(new ApiFootballSquadMember(name, primaryPosition, injured, expectedReturn));
            }
        }
        return result;
    }

    private String get(String path) {
        URI uri = URI.create(baseUrl + path);
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-rapidapi-key", apiKey);
        headers.set("x-rapidapi-host", uri.getHost());
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(uri, HttpMethod.GET, entity, String.class).getBody();
    }
}
