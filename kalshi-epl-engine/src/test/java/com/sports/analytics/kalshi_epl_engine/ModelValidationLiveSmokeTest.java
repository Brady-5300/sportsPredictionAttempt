package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real calibration check against live historical Kalshi + Understat
 * data. Disabled by default - this takes a while (roughly one Kalshi request
 * per historical event, rate-limit-paced) and hits both live sites for real.
 */
@Disabled("manual run only - hits live Kalshi + Understat, takes several minutes")
class ModelValidationLiveSmokeTest {

    @Test
    void runsARealValidationAndPrintsTheReport() {
        ScraperHealthMonitor healthMonitor = new ScraperHealthMonitor();
        UnderstatScraperService scraper = new UnderstatScraperService(healthMonitor);
        ShotXgCalculator shotCalc = new ShotXgCalculator();
        TeamNameResolver resolver = new TeamNameResolver();
        UnderstatXgProvider understatXgProvider = new UnderstatXgProvider(scraper, shotCalc, resolver);
        PoissonModel poissonModel = new PoissonModel();
        TickerParserService tickerParserService = new TickerParserService();

        FotMobClient fotMobClient = new FotMobClient("https://www.fotmob.com", new org.springframework.web.client.RestTemplate());
        LineupFormAdjustmentService lineupService = new LineupFormAdjustmentService(fotMobClient, understatXgProvider);
        XgService xgService = new XgService(resolver, understatXgProvider, lineupService);

        KalshiHistoricalClient historicalClient = new KalshiHistoricalClient();
        PredictionLogService predictionLogService = new PredictionLogService(historicalClient);
        KalshiMarketService kalshiMarketService = new KalshiMarketService(poissonModel, tickerParserService, xgService, healthMonitor, predictionLogService);

        ModelValidationService validation = new ModelValidationService(historicalClient, tickerParserService, understatXgProvider, poissonModel, kalshiMarketService);

        System.out.println("Running validation over up to 90 historical matches (this takes a while)...");
        ModelValidationReport report = validation.run(90);

        System.out.println("\n=== Model Validation Report ===");
        System.out.println("Markets total:         " + report.marketsTotal());
        System.out.println("Skipped (no xG data):  " + report.skippedNoXgData());
        System.out.println("Predictions evaluated: " + report.predictionsEvaluated());
        System.out.println("Brier score (0=perfect, 0.25=coin-flip-useless, 1=worst): " + report.brierScore());
        System.out.println("Log-loss:               " + report.logLoss());

        System.out.println("\nCalibration (predicted vs actual, by bucket):");
        System.out.printf("%-10s %6s %10s %10s%n", "Range", "Count", "AvgPred", "ActualFreq");
        for (CalibrationBucket b : report.calibrationBuckets()) {
            System.out.printf("%-10s %6d %10.3f %10.3f%n", b.rangeLabel(), b.count(), b.avgPredictedProbability(), b.actualFrequency());
        }

        assertTrue(report.marketsTotal() > 0, "expected at least some historical markets to be fetched");
    }
}
