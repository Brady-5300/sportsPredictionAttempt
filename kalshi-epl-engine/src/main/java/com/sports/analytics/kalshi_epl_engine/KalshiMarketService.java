package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
     * an outage. If FotMob (the lineup/injury layer) is offline, evaluation
     * proceeds normally with a warning - recommendations don't depend on it.
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

                    // Recommendations use the base model (no lineup/injury adjustment):
                    // it's the version validated on past seasons. The lineup-adjusted
                    // version is only logged, until the log shows it actually helps.
                    Optional<Double> homeXG = xgService.calculateHomeXG(homeTeam, awayTeam);
                    Optional<Double> awayXG = xgService.calculateAwayXG(homeTeam, awayTeam);
                    Optional<Double> lineupHomeXG = xgService.calculateHomeXG(homeTeam, awayTeam, matchDate);
                    Optional<Double> lineupAwayXG = xgService.calculateAwayXG(homeTeam, awayTeam, matchDate);

                    if (homeXG.isEmpty() || awayXG.isEmpty() || lineupHomeXG.isEmpty() || lineupAwayXG.isEmpty()) {
                        skipped.add(new SkippedMarket(ticker, fullTitle, "No live xG data for " + homeTeam + " and/or " + awayTeam
                            + " (not in our Understat mapping, or no completed matches yet this season)."));
                        continue;
                    }

                    String marketType = resolveMarketType(market, rawTitle, homeTeam, awayTeam);

                    double modelProb = poissonModel.calculateMarketProbability(homeXG.get(), awayXG.get(), marketType);
                    double lineupProb = poissonModel.calculateMarketProbability(lineupHomeXG.get(), lineupAwayXG.get(), marketType);

                    predictionLogService.recordSnapshot(ticker, fullTitle, marketType,
                        matchDate.map(LocalDate::toString).orElse(""), lineupProb, modelProb,
                        market.yesBidCents().orElse(null), market.yesAskCents().orElse(null),
                        market.estimatedKickoff(), xgService.lineupsConfirmed(homeTeam, awayTeam, matchDate));

                    int priceCents = market.resolvePriceCents();
                    // Skip if there's truly no active market pricing available
                    if (priceCents <= 0) {
                        continue;
                    }

                    // Buying YES costs the ask, not the bid, plus Kalshi's entry fee
                    // (settlement is free).
                    int buyPriceCents = market.yesAskCents().filter(ask -> ask > 0 && ask < 100).orElse(priceCents);
                    int entryFeeCents = poissonModel.calculateKalshiFeeCents(buyPriceCents);
                    double marketProb = buyPriceCents / 100.0;

                    double edge = modelProb - marketProb - entryFeeCents / 100.0;

                    // Only buying YES is evaluated - the NO side's price and fee are
                    // never checked - so a negative edge means "don't buy", not "bet NO".
                    String rec;
                    if (edge > 0.03) {
                        rec = "YES (Undervalued)";
                    } else if (edge < -0.03) {
                        rec = "AVOID";
                    } else {
                        rec = "FAIR VALUE";
                    }

                    double kellyWager = rec.startsWith("YES")
                        ? poissonModel.calculateKellyWagerPercent(modelProb, buyPriceCents + entryFeeCents)
                        : 0.0;

                    String modelStr = Math.round(modelProb * 10000.0) / 100.0 + "%";
                    String marketStr = Math.round(marketProb * 10000.0) / 100.0 + "%";
                    String edgeStr = Math.round(edge * 10000.0) / 100.0 + "%";

                    results.add(new MarketEvaluation(
                        ticker,
                        fullTitle,
                        buyPriceCents,
                        modelStr,
                        marketStr,
                        edgeStr,
                        rec,
                        kellyWager,
                        "understat-live",
                        market.estimatedKickoff().map(Instant::toString).orElse(null),
                        marketType,
                        market.yesBidCents().orElse(null),
                        market.yesAskCents().orElse(null)
                    ));
                }
            }
        } catch (Exception e) {
            System.err.println("[API ERROR] Error processing Kalshi events payload:");
            e.printStackTrace();
        }

        // Chronological by kickoff (ISO-8601 UTC strings sort correctly as text), unknown kickoffs last.
        results.sort(Comparator.comparing(MarketEvaluation::getKickoff, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(MarketEvaluation::getTicker));
        return MarketScanResult.ok(fotMobWarning(), results, skipped);
    }

    private String fotMobWarning() {
        return healthMonitor.isOffline(FotMobClient.SOURCE)
            ? "FotMob scraper appears offline - lineup/injury data was not available this scan."
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
