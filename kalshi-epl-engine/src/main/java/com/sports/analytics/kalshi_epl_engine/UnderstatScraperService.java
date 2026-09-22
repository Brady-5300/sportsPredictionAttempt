package com.sports.analytics.kalshi_epl_engine;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Fetches match/team data from understat.com. Understat has no public API, but
 * its front-end calls internal JSON endpoints ({@code /getTeamData/{team}/{season}}
 * and {@code /getMatchData/{matchId}}) that return plain JSON as long as the
 * request looks like an in-page AJAX call (they gate on the
 * X-Requested-With: XMLHttpRequest header - a plain GET without it 404s).
 *
 * This is still scraping an undocumented, unsanctioned endpoint - it can break
 * without notice if Understat changes their front end, so every method fails
 * gracefully (empty result) rather than throwing, so callers can fall back to
 * other data sources.
 */
@Service
public class UnderstatScraperService {

    /** Source name used with {@link ScraperHealthMonitor}. */
    public static final String SOURCE = "understat";

    private static final String BASE_URL = "https://understat.com";
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScraperHealthMonitor healthMonitor;

    public UnderstatScraperService(ScraperHealthMonitor healthMonitor) {
        this.healthMonitor = healthMonitor;
    }

    /**
     * Fetches a team's fixture list for a given season (Understat identifies a
     * season by its starting year, e.g. 2025 for the 2025/26 season).
     */
    public List<UnderstatTeamMatch> fetchTeamMatches(String understatTeamSlug, int season) {
        String url = BASE_URL + "/getTeamData/" + understatTeamSlug + "/" + season;
        try {
            String json = getAsAjax(url);
            List<UnderstatTeamMatch> result = parseTeamMatches(json);
            healthMonitor.recordSuccess(SOURCE);
            return result;
        } catch (Exception e) {
            System.err.println("[UNDERSTAT] Failed to fetch team matches for " + understatTeamSlug + ": " + e.getMessage());
            healthMonitor.recordFailure(SOURCE, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Fetches every shot from a completed match, split into home ("h") and away ("a") lists.
     */
    public Map<String, List<UnderstatShot>> fetchMatchShots(String matchId) {
        String url = BASE_URL + "/getMatchData/" + matchId;
        try {
            String json = getAsAjax(url);
            Map<String, List<UnderstatShot>> result = parseMatchShots(json);
            healthMonitor.recordSuccess(SOURCE);
            return result;
        } catch (Exception e) {
            System.err.println("[UNDERSTAT] Failed to fetch shots for match " + matchId + ": " + e.getMessage());
            healthMonitor.recordFailure(SOURCE, e.getMessage());
            return Map.of("h", new ArrayList<>(), "a", new ArrayList<>());
        }
    }

    /**
     * Fetches both shots and player rosters (minutes played, position) for a match
     * in a single request - use this instead of {@link #fetchMatchShots} when you
     * also need per-player minutes, to avoid hitting the endpoint twice.
     */
    public UnderstatMatchDetails fetchMatchDetails(String matchId) {
        String url = BASE_URL + "/getMatchData/" + matchId;
        try {
            String json = getAsAjax(url);
            UnderstatMatchDetails result = new UnderstatMatchDetails(parseMatchShots(json), parseMatchRosters(json));
            healthMonitor.recordSuccess(SOURCE);
            return result;
        } catch (Exception e) {
            System.err.println("[UNDERSTAT] Failed to fetch match details for " + matchId + ": " + e.getMessage());
            healthMonitor.recordFailure(SOURCE, e.getMessage());
            return UnderstatMatchDetails.empty();
        }
    }

    List<UnderstatTeamMatch> parseTeamMatches(String json) {
        if (json == null) return new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode dates = root.get("dates");
        if (dates == null || !dates.isArray()) return new ArrayList<>();

        List<UnderstatTeamMatch> result = new ArrayList<>();
        for (JsonNode node : dates) {
            result.add(objectMapper.treeToValue(node, UnderstatTeamMatch.class));
        }
        return result;
    }

    Map<String, List<UnderstatShot>> parseMatchShots(String json) {
        Map<String, List<UnderstatShot>> empty = Map.of("h", new ArrayList<>(), "a", new ArrayList<>());
        if (json == null) return empty;

        JsonNode root = objectMapper.readTree(json);
        JsonNode shots = root.get("shots");
        if (shots == null) return empty;

        List<UnderstatShot> home = new ArrayList<>();
        JsonNode hNode = shots.get("h");
        if (hNode != null && hNode.isArray()) {
            for (JsonNode node : hNode) home.add(objectMapper.treeToValue(node, UnderstatShot.class));
        }

        List<UnderstatShot> away = new ArrayList<>();
        JsonNode aNode = shots.get("a");
        if (aNode != null && aNode.isArray()) {
            for (JsonNode node : aNode) away.add(objectMapper.treeToValue(node, UnderstatShot.class));
        }

        return Map.of("h", home, "a", away);
    }

    /**
     * Rosters are keyed by player id ("h":{"666295":{...},"666296":{...}}), not arrays.
     */
    Map<String, List<UnderstatPlayerMatchStat>> parseMatchRosters(String json) {
        Map<String, List<UnderstatPlayerMatchStat>> empty = Map.of("h", new ArrayList<>(), "a", new ArrayList<>());
        if (json == null) return empty;

        JsonNode root = objectMapper.readTree(json);
        JsonNode rosters = root.get("rosters");
        if (rosters == null) return empty;

        return Map.of(
            "h", parseRosterSide(rosters.get("h")),
            "a", parseRosterSide(rosters.get("a"))
        );
    }

    private List<UnderstatPlayerMatchStat> parseRosterSide(JsonNode sideNode) {
        List<UnderstatPlayerMatchStat> result = new ArrayList<>();
        if (sideNode == null || !sideNode.isObject()) return result;

        sideNode.propertyStream().forEach(entry ->
            result.add(objectMapper.treeToValue(entry.getValue(), UnderstatPlayerMatchStat.class))
        );
        return result;
    }

    /**
     * Understat's server gzips these responses regardless of the client's
     * Accept-Encoding header, and RestTemplate's default request factory does
     * not transparently decompress - so fetch raw bytes and decode manually.
     */
    private String getAsAjax(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Requested-With", "XMLHttpRequest");
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        byte[] body = restTemplate.exchange(url, HttpMethod.GET, entity, byte[].class).getBody();
        if (body == null) return null;
        return isGzip(body) ? gunzip(body) : new String(body, StandardCharsets.UTF_8);
    }

    private boolean isGzip(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    private String gunzip(byte[] bytes) {
        try (GZIPInputStream gzipIn = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            gzipIn.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to gunzip Understat response", e);
        }
    }
}
