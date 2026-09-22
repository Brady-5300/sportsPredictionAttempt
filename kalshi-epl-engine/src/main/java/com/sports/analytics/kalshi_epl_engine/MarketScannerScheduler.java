package com.sports.analytics.kalshi_epl_engine;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MarketScannerScheduler {

    private final KalshiMarketService marketService;

    public MarketScannerScheduler(KalshiMarketService marketService) {
        this.marketService = marketService;
    }

    // Automatically polls Kalshi markets every 5 minutes (300,000 ms)
    @Scheduled(fixedRate = 300000)
    public void scanMarketsPeriodically() {
        System.out.println("[SCANNER] Running automated Kalshi EPL market check...");

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
