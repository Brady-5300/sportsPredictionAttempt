package com.sports.analytics.kalshi_epl_engine;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Runs the actual model calibration against real Understat data. Disabled by
 * default - this scrapes ~200+ matches (a couple hundred HTTP requests) and
 * takes a few minutes. Run manually whenever ShotXgCalculator's coefficients
 * need recalibrating (e.g. a new season's worth of data is available), then
 * copy the printed weights into ShotXgCalculator's logit formula.
 */
@Disabled("manual calibration run only - scrapes ~200+ real matches from understat.com")
class XgModelCalibrationTest {

    @Test
    void calibrateAgainstLastFullSeason() {
        UnderstatScraperService scraper = new UnderstatScraperService();
        ShotXgCalculator geometry = new ShotXgCalculator();
        ShotFeatureExtractor featureExtractor = new ShotFeatureExtractor(geometry);
        XgModelCalibrator calibrator = new XgModelCalibrator(scraper, featureExtractor);

        // A spread of styles/quality so the fit isn't skewed toward one kind of team.
        List<String> teams = List.of(
            "Arsenal", "Manchester_City", "Chelsea", "Everton",
            "Brighton", "Fulham", "Brentford", "Nottingham_Forest"
        );

        System.out.println("Collecting shots from " + teams.size() + " teams' 2025/26 season schedules...");
        XgModelCalibrator.Dataset dataset = calibrator.collectDataset(teams, 2025);
        System.out.println("Matches used: " + dataset.matchesUsed());
        System.out.println("Trainable shots: " + dataset.examples().size());

        long goals = dataset.examples().stream().filter(e -> e.label() == 1).count();
        System.out.println("Goals: " + goals + " (" + String.format("%.2f%%", 100.0 * goals / dataset.examples().size()) + ")");

        LogisticRegressionTrainer trainer = new LogisticRegressionTrainer(0.05, 3000, 0.0005);
        LogisticRegressionTrainer.TrainingResult result = trainer.fit(dataset.examples());

        System.out.println("\n=== Fitted weights (bias, distance, angle, corner, setPiece, directFreekick, fastBreak, header, otherBodyPart) ===");
        for (double w : result.weights()) {
            System.out.printf("%.6f%n", w);
        }
        System.out.println("\nFinal log-loss: " + result.finalLogLoss());
        System.out.println("Calibration ratio (predicted/actual goals, ~1.0 is well-calibrated): "
            + calibrator.calibrationRatio(trainer, result.weights(), dataset.examples()));
    }
}
