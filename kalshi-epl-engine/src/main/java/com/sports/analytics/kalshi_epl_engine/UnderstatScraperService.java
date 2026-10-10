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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private final RestTemplate restTemplate = HttpClients.withTimeouts();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScraperHealthMonitor healthMonitor;

    // A completed match's shots/rosters never change once played, so this can be
    // cached permanently rather than re-fetched - matters a lot for backtesting,
    // where the same historical match gets asked about repeatedly across many
    // different "as of" rating windows. Only successful fetches are cached; a
    // transient failure must not get permanently remembered as "no data".
    private final ConcurrentHashMap<String, UnderstatMatchDetails> matchDetailsCache = new ConcurrentHashMap<>();

    // Fixture lists change (results come in), so only cache briefly - long
    // enough that a validation run asking about the same team/season hundreds
    // of times doesn't re-download it every time.
    private static final long TEAM_MATCHES_TTL_SECONDS = 10 * 60;
    private record CachedTeamMatches(List<UnderstatTeamMatch> matches, Instant fetchedAt) {
    }
    private final ConcurrentHashMap<String, CachedTeamMatches> teamMatchesCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, List<String>> leagueTeamsCache = new ConcurrentHashMap<>();

    // Season player totals change after every match, but nobody needs them to the minute.
    private static final long LEAGUE_PLAYERS_TTL_SECONDS = 60 * 60;
    private record CachedPlayers(List<UnderstatPlayerSeason> players, Instant fetchedAt) {
    }
    private final ConcurrentHashMap<Integer, CachedPlayers> leaguePlayersCache = new ConcurrentHashMap<>();

    public UnderstatScraperService(ScraperHealthMonitor healthMonitor) {
        this.healthMonitor = healthMonitor;
    }

    /**
     * Understat's own season totals for every EPL player (goals, assists, and
     * Understat's xG/xA) - display only; the model uses its own shot-based xG.
     */
    public List<UnderstatPlayerSeason> fetchLeaguePlayers(int season) {
        CachedPlayers cached = leaguePlayersCache.get(season);
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).getSeconds() < LEAGUE_PLAYERS_TTL_SECONDS) {
            return cached.players();
        }

        String url = BASE_URL + "/getLeagueData/EPL/" + season;
        try {
            List<UnderstatPlayerSeason> players = parseLeaguePlayers(getAsAjax(url));
            healthMonitor.recordSuccess(SOURCE);
            if (!players.isEmpty()) leaguePlayersCache.put(season, new CachedPlayers(players, Instant.now()));
            return players;
        } catch (Exception e) {
            System.err.println("[UNDERSTAT] Failed to fetch league players for " + season + ": " + e.getMessage());
            healthMonitor.recordFailure(SOURCE, e.getMessage());
            return cached != null ? cached.players() : List.of();
        }
    }

    List<UnderstatPlayerSeason> parseLeaguePlayers(String json) {
        if (json == null) return List.of();
        List<UnderstatPlayerSeason> players = new ArrayList<>();
        for (JsonNode p : objectMapper.readTree(json).path("players")) {
            String name = p.path("player_name").asString(null);
            String team = p.path("team_title").asString(null);
            if (name == null || team == null) continue;
            players.add(new UnderstatPlayerSeason(name, team, p.path("position").asString(""),
                p.path("games").asInt(0), p.path("time").asInt(0),
                p.path("goals").asInt(0), p.path("assists").asInt(0),
                p.path("xG").asDouble(0.0), p.path("xA").asDouble(0.0)));
        }
        return List.copyOf(players);
    }

    /**
     * Names of every team in the EPL for a season (Understat titles, e.g.
     * "Wolverhampton Wanderers"). Cached permanently once fetched - a season's
     * teams don't change - and never cached on failure.
     */
    public List<String> fetchLeagueTeamTitles(int season) {
        List<String> cached = leagueTeamsCache.get(season);
        if (cached != null) return cached;

        String url = BASE_URL + "/getLeagueData/EPL/" + season;
        try {
            JsonNode teams = objectMapper.readTree(getAsAjax(url)).path("teams");
            List<String> titles = new ArrayList<>();
            for (JsonNode team : teams) {
                String title = team.path("title").asString(null);
                if (title != null) titles.add(title);
            }
            healthMonitor.recordSuccess(SOURCE);
            if (!titles.isEmpty()) leagueTeamsCache.put(season, List.copyOf(titles));
            return titles;
        } catch (Exception e) {
            System.err.println("[UNDERSTAT] Failed to fetch league teams for " + season + ": " + e.getMessage());
            healthMonitor.recordFailure(SOURCE, e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetches a team's fixture list for a given season (Understat identifies a
     * season by its starting year, e.g. 2025 for the 2025/26 season).
     */
    public List<UnderstatTeamMatch> fetchTeamMatches(String understatTeamSlug, int season) {
        String cacheKey = understatTeamSlug + "/" + season;
        CachedTeamMatches cached = teamMatchesCache.get(cacheKey);
        if (cached != null && Duration.between(cached.fetchedAt(), Instant.now()).getSeconds() < TEAM_MATCHES_TTL_SECONDS) {
            return cached.matches();
        }

        String url = BASE_URL + "/getTeamData/" + understatTeamSlug + "/" + season;
        try {
            String json = getAsAjax(url);
            List<UnderstatTeamMatch> result = List.copyOf(parseTeamMatches(json));
            healthMonitor.recordSuccess(SOURCE);
            teamMatchesCache.put(cacheKey, new CachedTeamMatches(result, Instant.now()));
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
        UnderstatMatchDetails cached = matchDetailsCache.get(matchId);
        if (cached != null) return cached;

        String url = BASE_URL + "/getMatchData/" + matchId;
        try {
            String json = getAsAjax(url);
            UnderstatMatchDetails result = new UnderstatMatchDetails(parseMatchShots(json), parseMatchRosters(json));
            healthMonitor.recordSuccess(SOURCE);
            matchDetailsCache.put(matchId, result);
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
