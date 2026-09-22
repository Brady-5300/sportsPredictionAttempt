package com.sports.analytics.kalshi_epl_engine;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

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

        List<MarketEvaluation> evaluations = marketService.evaluateLiveMarkets(1.50, 1.00);

        for (MarketEvaluation eval : evaluations) {
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