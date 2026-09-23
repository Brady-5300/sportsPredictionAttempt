package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class KalshiMarketService {

    private final String KALSHI_URL = "https://external-api.kalshi.com/trade-api/v2/events?series_ticker=KXEPLGAME&with_nested_markets=true";
    private final RestTemplate restTemplate = new RestTemplate();
    private final PoissonModel poissonModel;
    private final TickerParserService tickerParserService;
    private final XgService xgService;
    private final ScraperHealthMonitor healthMonitor;
    private final PredictionLogService predictionLogService;

    public KalshiMarketService(PoissonModel poissonModel,
                                TickerParserService tickerParserService,
                                XgService xgService,
                                ScraperHealthMonitor healthMonitor,
                                PredictionLogService predictionLogService) {
        this.poissonModel = poissonModel;
        this.tickerParserService = tickerParserService;
        this.xgService = xgService;
        this.healthMonitor = healthMonitor;
        this.predictionLogService = predictionLogService;
    }

    /**
     * Scans all active Kalshi EPL markets and evaluates each against our model.
     *
     * If Understat (the core xG data source) appears offline, this halts
     * immediately and returns a status describing that, with no evaluations at
     * all - we never silently substitute static ratings for real data. If a
     * specific team just has no live data (e.g. not in our Understat mapping),
     * that market is skipped individually with a reason, rather than treated as
     * an outage. If FotMob (the lineup/injury refinement layer) is offline,
     * evaluation proceeds normally but with a warning that those adjustments
     * weren't applied this scan.
     */
    public MarketScanResult evaluateLiveMarkets() {
        if (healthMonitor.isOffline(UnderstatScraperService.SOURCE)) {
            return MarketScanResult.offline(UnderstatScraperService.SOURCE, healthMonitor.lastFailureReason(UnderstatScraperService.SOURCE));
        }

        List<MarketEvaluation> results = new ArrayList<>();
        List<SkippedMarket> skipped = new ArrayList<>();

        try {
            KalshiMarketResponse response = restTemplate.getForObject(KALSHI_URL, KalshiMarketResponse.class);
            if (response == null) {
                return MarketScanResult.ok(fotMobWarning(), results, skipped);
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
                    String ticker = market.getTicker() == null ? "N/A" : market.getTicker();

                    // A core-source outage detected mid-scan (started healthy, failed partway
                    // through) - stop evaluating rather than mix real and now-stale data.
                    if (healthMonitor.isOffline(UnderstatScraperService.SOURCE)) {
                        return MarketScanResult.offline(UnderstatScraperService.SOURCE, healthMonitor.lastFailureReason(UnderstatScraperService.SOURCE));
                    }

                    Optional<LocalDate> matchDate = tickerParserService.parseMatchDateFromTicker(market.getTicker());

                    Optional<Double> homeXG = xgService.calculateHomeXG(homeTeam, awayTeam, matchDate);
                    Optional<Double> awayXG = xgService.calculateAwayXG(homeTeam, awayTeam, matchDate);

                    if (homeXG.isEmpty() || awayXG.isEmpty()) {
                        skipped.add(new SkippedMarket(ticker, fullTitle, "No live xG data for " + homeTeam + " and/or " + awayTeam
                            + " (not in our Understat mapping, or no completed matches yet this season)."));
                        continue;
                    }

                    String marketType = resolveMarketType(market, rawTitle, homeTeam, awayTeam);

                    double modelProb = poissonModel.calculateMarketProbability(homeXG.get(), awayXG.get(), marketType);

                    predictionLogService.logIfNew(ticker, fullTitle, marketType,
                        matchDate.map(LocalDate::toString).orElse(""), modelProb);

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

                    results.add(new MarketEvaluation(
                        ticker,
                        fullTitle,
                        priceCents,
                        modelStr,
                        marketStr,
                        edgeStr,
                        rec,
                        kellyWager,
                        "understat-live"
                    ));
                }
            }
        } catch (Exception e) {
            System.err.println("[API ERROR] Error processing Kalshi events payload:");
            e.printStackTrace();
        }

        return MarketScanResult.ok(fotMobWarning(), results, skipped);
    }

    private String fotMobWarning() {
        return healthMonitor.isOffline(FotMobClient.SOURCE)
            ? "FotMob scraper appears offline - lineup/injury adjustments were not applied this scan."
            : null;
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
