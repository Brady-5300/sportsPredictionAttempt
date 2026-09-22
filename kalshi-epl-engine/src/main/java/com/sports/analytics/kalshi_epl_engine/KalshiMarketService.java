package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class KalshiMarketService {

    private final String KALSHI_URL = "https://external-api.kalshi.com/trade-api/v2/events?series_ticker=KXEPLGAME&with_nested_markets=true";
    private final RestTemplate restTemplate = new RestTemplate();
    private final PoissonModel poissonModel;
    private final TickerParserService tickerParserService;
    private final XgService xgService;

    public KalshiMarketService(PoissonModel poissonModel,
                                TickerParserService tickerParserService,
                                XgService xgService) {
        this.poissonModel = poissonModel;
        this.tickerParserService = tickerParserService;
        this.xgService = xgService;
    }

    public List<MarketEvaluation> evaluateLiveMarkets(double defaultHomeXG, double defaultAwayXG) {
        List<MarketEvaluation> results = new ArrayList<>();
        try {
            KalshiMarketResponse response = restTemplate.getForObject(KALSHI_URL, KalshiMarketResponse.class);
            if (response == null) {
                return results;
            }

            for (KalshiEvent event : response.getEvents()) {
                String eventTitle = event.getTitle() == null ? "" : event.getTitle();

                for (KalshiMarket market : event.getMarkets()) {
                    String status = market.getStatus() == null ? "active" : market.getStatus();
                    if (!status.equalsIgnoreCase("active")) {
                        continue;
                    }

                    String rawTitle = market.getTitle() == null ? "" : market.getTitle();
                    String fullTitle = (rawTitle.toLowerCase().contains("vs") || rawTitle.toLowerCase().contains("beat"))
                        ? rawTitle
                        : eventTitle + ": " + rawTitle;

                    Map.Entry<String, String> teams = tickerParserService.extractTeamsFromTitle(fullTitle);
                    if (teams == null || teams.getKey() == null || teams.getValue() == null) {
                        continue;
                    }

                    String homeTeam = teams.getKey().replaceAll("(?i)\\s+(wins|is the result)$", "").trim();
                    String awayTeam = teams.getValue().replaceAll("(?i)\\s+(wins|is the result)$", "").trim();

                    double homeXG = xgService.calculateHomeXG(homeTeam, awayTeam);
                    double awayXG = xgService.calculateAwayXG(homeTeam, awayTeam);

                    String marketType = resolveMarketType(market, rawTitle, homeTeam, awayTeam);

                    double modelProb = poissonModel.calculateMarketProbability(homeXG, awayXG, marketType);

                    int priceCents = market.resolvePriceCents();
                    // Skip if there's truly no active market pricing available
                    if (priceCents <= 0) {
                        continue;
                    }

                    double marketProb = priceCents / 100.0;

                    // Kalshi charges a trading fee on entry (settlement itself is free),
                    // so a real trade needs to clear the market's implied probability by
                    // more than the raw model edge suggests.
                    int entryFeeCents = poissonModel.calculateKalshiFeeCents(priceCents);
                    double entryFeeProb = entryFeeCents / 100.0;

                    double edge = modelProb - marketProb - entryFeeProb;

                    double kellyWager = poissonModel.calculateKellyWagerPercent(modelProb, priceCents);

                    String modelStr = Math.round(modelProb * 10000.0) / 100.0 + "%";
                    String marketStr = Math.round(marketProb * 10000.0) / 100.0 + "%";
                    String edgeStr = Math.round(edge * 10000.0) / 100.0 + "%";

                    String rec;
                    if (edge > 0.03) {
                        rec = "YES (Undervalued)";
                    } else if (edge < -0.03) {
                        rec = "NO (Overvalued)";
                    } else {
                        rec = "FAIR VALUE";
                    }

                    String ticker = market.getTicker() == null ? "N/A" : market.getTicker();
                    String xgDataSource = xgService.hasLiveDataFor(homeTeam, awayTeam) ? "understat-live" : "static-fallback";

                    results.add(new MarketEvaluation(
                        ticker,
                        fullTitle,
                        priceCents,
                        modelStr,
                        marketStr,
                        edgeStr,
                        rec,
                        kellyWager,
                        xgDataSource
                    ));
                }
            }
        } catch (Exception e) {
            System.err.println("[API ERROR] Error processing Kalshi events payload:");
            e.printStackTrace();
        }

        return results;
    }

    /**
     * Determines whether a market resolves on the home win, away win, or tie outcome.
     *
     * Primary signal is an exact match between the ticker's outcome suffix (e.g. "-MUN")
     * and each team's canonical code, which avoids the false positives that substring
     * matching produces (e.g. "SUN" (Sunderland) being a substring of another code, or a
     * team name embedded inside another team's name). Falls back to title-text cues only
     * when the ticker doesn't carry a recognizable code.
     */
    String resolveMarketType(KalshiMarket market, String rawTitle, String homeTeam, String awayTeam) {
        String rawMarketTitleLower = rawTitle.toLowerCase();
        if (rawMarketTitleLower.contains("tie") || rawMarketTitleLower.contains("draw")) {
            return "TIE";
        }

        String tickerUpper = market.getTicker() == null ? "" : market.getTicker().toUpperCase();
        if (tickerUpper.endsWith("-TIE") || tickerUpper.endsWith("-T")) {
            return "TIE";
        }

        String[] tickerParts = tickerUpper.split("-");
        String suffix = tickerParts.length > 0 ? tickerParts[tickerParts.length - 1] : "";

        String homeCode = xgService.getTeamCode(homeTeam);
        String awayCode = xgService.getTeamCode(awayTeam);

        if (!suffix.isEmpty()) {
            if (!awayCode.isEmpty() && suffix.equals(awayCode)) {
                return "AWAY";
            }
            if (!homeCode.isEmpty() && suffix.equals(homeCode)) {
                return "HOME";
            }
        }

        // Fallback: only reachable when the team code couldn't be resolved
        // (e.g. an unrecognized/newly promoted club not in our code table).
        if (rawMarketTitleLower.contains(awayTeam.toLowerCase()) && !rawMarketTitleLower.contains(homeTeam.toLowerCase())) {
            return "AWAY";
        }
        if (rawMarketTitleLower.contains(homeTeam.toLowerCase()) && !rawMarketTitleLower.contains(awayTeam.toLowerCase())) {
            return "HOME";
        }

        return "HOME";
    }

    public String fetchRawMarkets() {
        try {
            String jsonResponse = restTemplate.getForObject(KALSHI_URL, String.class);
            return jsonResponse != null ? jsonResponse : "{\"error\": \"Empty response\"}";
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
}
