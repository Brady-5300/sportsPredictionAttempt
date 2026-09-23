package com.sports.analytics.kalshi_epl_engine;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Separate from MarketScannerScheduler's 5-minute active-market scan: this
 * periodically checks back on predictions already logged, to see whether
 * Kalshi has settled them yet, and records the real outcome once it has.
 * Runs less often since match settlement isn't time-sensitive the way a live
 * trading edge would be.
 */
@Component
public class PredictionResolutionScheduler {

    private final PredictionLogService predictionLogService;

    public PredictionResolutionScheduler(PredictionLogService predictionLogService) {
        this.predictionLogService = predictionLogService;
    }

    // Every 30 minutes.
    @Scheduled(fixedRate = 1800000)
    public void checkForResolutions() {
        int resolved = predictionLogService.checkForResolutions();
        if (resolved > 0) {
            System.out.println("[PREDICTION-LOG] Resolved " + resolved + " previously-logged prediction(s).");
        }
    }
}
