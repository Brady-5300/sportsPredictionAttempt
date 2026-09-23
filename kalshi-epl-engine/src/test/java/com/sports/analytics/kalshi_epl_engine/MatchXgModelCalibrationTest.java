package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Runs the actual match-level xG formula calibration against real Understat
 * data. Disabled by default - scrapes many real matches (point-in-time
 * ratings mean each match needs its own rolling-window lookups) and takes a
 * few minutes. Run manually whenever XgService's formula needs recalibrating,
 * then copy the printed weights into XgService.combineAttackDefense.
 */
@Disabled("manual calibration run only - scrapes real matches from understat.com")
class MatchXgModelCalibrationTest {

    @Test
    void calibrateAgainstCurrentSeason() {
        ScraperHealthMonitor healthMonitor = new ScraperHealthMonitor();
        UnderstatScraperService scraper = new UnderstatScraperService(healthMonitor);
        ShotXgCalculator shotCalc = new ShotXgCalculator();
        TeamNameResolver resolver = new TeamNameResolver();
        UnderstatXgProvider understatXgProvider = new UnderstatXgProvider(scraper, shotCalc, resolver);
        MatchXgModelCalibrator calibrator = new MatchXgModelCalibrator(scraper, understatXgProvider, resolver);

        // Every team we have an Understat slug mapping for (see TeamNameResolver).
        List<String> teams = List.of(
            "Arsenal", "Manchester City", "Liverpool", "Chelsea", "Tottenham",
            "Aston Villa", "Newcastle", "Manchester United", "Brighton", "Crystal Palace",
            "Fulham", "Brentford", "Bournemouth", "Everton", "Nottingham Forest",
            "Sunderland", "Leeds United"
        );

        System.out.println("Collecting match examples from " + teams.size() + " teams' schedules...");
        MatchXgModelCalibrator.Dataset dataset = calibrator.collectDataset(teams, 2025);
        System.out.println("Matches used: " + dataset.matchesUsed());
        System.out.println("Skipped (no rating available): " + dataset.matchesSkippedNoRating());
        System.out.println("Training examples: " + dataset.examples().size());

        PoissonRegressionTrainer trainer = new PoissonRegressionTrainer(0.02, 3000, 0.001);
        PoissonRegressionTrainer.TrainingResult result = trainer.fit(dataset.examples());

        System.out.println("\n=== Fitted weights (bias, ownAttack, opponentDefense, isHome) ===");
        for (double w : result.weights()) {
            System.out.printf("%.6f%n", w);
        }
        System.out.println("\nFinal deviance: " + result.finalDeviance());
        System.out.println("Calibration ratio (predicted/actual goals, ~1.0 is well-calibrated): "
            + calibrator.calibrationRatio(trainer, result.weights(), dataset.examples()));

        // Sanity check: what does the fit imply about home advantage in isolation?
        double homeMultiplier = Math.exp(result.weights()[3]);
        System.out.println("\nImplied home-advantage multiplier (exp(isHome weight)): " + homeMultiplier
            + " (>1.0 means home teams score more, all else equal)");
    }
}
