package com.sports.analytics.kalshi_epl_engine;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MarketScannerScheduler {

    // Lineups come out about 60-75 minutes before kickoff. From this long before
    // a kickoff until the match starts, scan every minute instead of every 5,
    // so the lineup-speed test sees lineups (and Kalshi's reaction) promptly.
    static final Duration LINEUP_WATCH_WINDOW = Duration.ofMinutes(100);

    private final KalshiMarketService marketService;
    private final PredictionLogService predictionLogService;

    public MarketScannerScheduler(KalshiMarketService marketService, PredictionLogService predictionLogService) {
        this.marketService = marketService;
        this.predictionLogService = predictionLogService;
    }

    // Automatically polls Kalshi markets every 5 minutes (300,000 ms)
    @Scheduled(fixedRate = 300000)
    public void scanMarketsPeriodically() {
        System.out.println("[SCANNER] Running automated Kalshi EPL market check...");
        scan();
    }

    // Every minute, but only scans while a match is close to kickoff. Spring runs
    // scheduled tasks one at a time, so this never overlaps the 5-minute scan.
    @Scheduled(fixedRate = 60000, initialDelay = 60000)
    public void scanAroundLineups() {
        if (!predictionLogService.hasMatchKickingOffWithin(LINEUP_WATCH_WINDOW)) return;
        System.out.println("[SCANNER] Match close to kickoff - checking lineups and prices...");
        scan();
    }

    private void scan() {

        MarketScanResult result = marketService.evaluateLiveMarkets();

        if (!MarketScanResult.STATUS_OK.equals(result.status())) {
            System.out.println("[SCANNER] " + result.message());
            return;
        }

        if (result.warning() != null) {
            System.out.println("[SCANNER] WARNING: " + result.warning());
        }

        for (SkippedMarket skip : result.skipped()) {
            System.out.println("[SCANNER] Skipped " + skip.ticker() + ": " + skip.reason());
        }

        for (MarketEvaluation eval : result.evaluations()) {
            if ("YES (Undervalued)".equals(eval.getRecommendation())) {
                System.out.println("==========================================");
                System.out.println("EDGE FOUND: " + eval.getTitle());
                System.out.println("Ticker: " + eval.getTicker());
                System.out.println("Price: " + eval.getKalshiPriceCents() + "¢");
                System.out.println("Model Prob: " + eval.getModelProbability() + " | Market Prob: " + eval.getMarketProbability());
                System.out.println("Edge: " + eval.getEdge());
                System.out.println("==========================================");
            }
        }
    }
}
