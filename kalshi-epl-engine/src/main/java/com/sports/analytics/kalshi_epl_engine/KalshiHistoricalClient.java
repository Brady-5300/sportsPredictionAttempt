package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fetches settled (historical) Kalshi EPL events/markets, for validating the
 * model's calibration against real outcomes.
 */
@Service
public class KalshiHistoricalClient {

    private static final String BASE_URL = "https://external-api.kalshi.com/trade-api/v2";
    // Fetching many events in a row (one events-list call + one markets call per
    // event) trips Kalshi's rate limit on unauthenticated callers (confirmed
    // empirically - hit 429s without this), so pace requests and retry with
    // backoff on a 429 rather than failing the whole run.
    private static final long REQUEST_PACING_MS = 250;
    private static final int MAX_RETRIES = 5;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Fetches settled KXEPLGAME events (one per historical match), each with
     * its home/away/tie markets populated.
     *
     * IMPORTANT: Kalshi's {@code with_nested_markets=true} flag silently stops
     * working once a {@code cursor} is present (confirmed empirically - the
     * first page returns nested markets fine, but every subsequent page comes
     * back with no "markets" field at all, no error). So this fetches plain
     * event tickers/titles across all pages (reliable), then fetches each
     * event's markets separately via {@code /markets?event_ticker=...} (also
     * confirmed reliable) - one extra request per event, but correct.
     */
    public List<KalshiEvent> fetchSettledEvents(int maxEvents) {
        List<KalshiEvent> result = new ArrayList<>();
        String cursor = null;

        while (result.size() < maxEvents) {
            String url = BASE_URL + "/events?series_ticker=KXEPLGAME&status=settled&limit=200"
                + (cursor != null ? "&cursor=" + cursor : "");
            String json = getWithRetry(url);
            if (json == null) break;

            JsonNode root = objectMapper.readTree(json);
            JsonNode events = root.get("events");
            if (events == null || !events.isArray() || events.isEmpty()) break;

            for (JsonNode eventNode : events) {
                String eventTicker = eventNode.path("event_ticker").asString(null);
                String title = eventNode.path("title").asString(null);
                if (eventTicker == null) continue;

                KalshiEvent event = new KalshiEvent();
                event.setTitle(title);
                event.setMarkets(fetchMarketsForEvent(eventTicker));
                result.add(event);

                if (result.size() >= maxEvents) break;
            }

            JsonNode cursorNode = root.get("cursor");
            cursor = cursorNode == null ? null : cursorNode.asString(null);
            if (cursor == null || cursor.isBlank()) break;
        }

        return result;
    }

    /**
     * Looks up a single market by ticker - used to check whether a
     * previously-logged live prediction has settled yet, without paginating
     * through the whole settled-events list.
     */
    public Optional<KalshiMarket> fetchSingleMarket(String ticker) {
        String url = BASE_URL + "/markets/" + ticker;
        String json = getWithRetry(url);
        if (json == null) return Optional.empty();

        JsonNode root = objectMapper.readTree(json);
        JsonNode marketNode = root.get("market");
        if (marketNode == null) return Optional.empty();

        return Optional.of(objectMapper.treeToValue(marketNode, KalshiMarket.class));
    }

    private List<KalshiMarket> fetchMarketsForEvent(String eventTicker) {
        String url = BASE_URL + "/markets?event_ticker=" + eventTicker;
        String json = getWithRetry(url);
        if (json == null) return List.of();

        JsonNode root = objectMapper.readTree(json);
        JsonNode markets = root.get("markets");
        if (markets == null || !markets.isArray()) return List.of();

        List<KalshiMarket> result = new ArrayList<>();
        for (JsonNode marketNode : markets) {
            result.add(objectMapper.treeToValue(marketNode, KalshiMarket.class));
        }
        return result;
    }

    /** Paces every request and retries with backoff on a 429, since a validation run makes many calls in a row. */
    private String getWithRetry(String url) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                Thread.sleep(REQUEST_PACING_MS);
                return restTemplate.getForObject(url, String.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                long backoffMs = REQUEST_PACING_MS * (long) Math.pow(2, attempt + 1);
                System.err.println("[KALSHI-HISTORICAL] Rate limited, backing off " + backoffMs + "ms (attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")");
                sleepQuietly(backoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        System.err.println("[KALSHI-HISTORICAL] Giving up on " + url + " after " + MAX_RETRIES + " retries");
        return null;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
