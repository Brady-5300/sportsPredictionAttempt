package com.sports.analytics.kalshi_epl_engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline tool to calibrate {@link ShotXgCalculator}'s coefficients against
 * real Understat shot outcome data, instead of the hand-picked constants it
 * shipped with. Not part of the live application - run manually (see
 * XgModelCalibrationTest) whenever recalibration is wanted, then paste the
 * fitted coefficients into ShotXgCalculator.
 *
 * Pulls full-season schedules for a handful of teams (chosen to span a mix of
 * styles/quality) rather than iterating every team, since fetching one team's
 * schedule and then every one of its matches' shots also captures every
 * opponent's shots in those same matches "for free" - a handful of teams'
 * schedules already covers most of the league without redundant requests.
 */
public class XgModelCalibrator {

    private final UnderstatScraperService scraper;
    private final ShotFeatureExtractor featureExtractor;

    public XgModelCalibrator(UnderstatScraperService scraper, ShotFeatureExtractor featureExtractor) {
        this.scraper = scraper;
        this.featureExtractor = featureExtractor;
    }

    public record Dataset(List<LogisticRegressionTrainer.LabeledExample> examples, int matchesUsed) {
    }

    /**
     * Collects a training dataset by pulling every match from each given team's
     * season schedule, deduplicating by match id (two teams in the sample can
     * share a fixture), and extracting every trainable shot from each match.
     */
    public Dataset collectDataset(List<String> understatTeamSlugs, int season) {
        Map<String, Boolean> seenMatchIds = new LinkedHashMap<>();
        List<LogisticRegressionTrainer.LabeledExample> examples = new ArrayList<>();

        for (String slug : understatTeamSlugs) {
            List<UnderstatTeamMatch> matches = scraper.fetchTeamMatches(slug, season);
            for (UnderstatTeamMatch match : matches) {
                if (!match.isResult()) continue;
                if (seenMatchIds.putIfAbsent(match.getId(), true) != null) continue; // already fetched via another team

                UnderstatMatchDetails details = scraper.fetchMatchDetails(match.getId());
                addExamplesFrom(details.shots().get("h"), examples);
                addExamplesFrom(details.shots().get("a"), examples);
            }
        }

        return new Dataset(examples, seenMatchIds.size());
    }

    private void addExamplesFrom(List<UnderstatShot> shots, List<LogisticRegressionTrainer.LabeledExample> examples) {
        if (shots == null) return;
        for (UnderstatShot shot : shots) {
            if (!featureExtractor.isTrainable(shot)) continue;
            int label = featureExtractor.isGoal(shot) ? 1 : 0;
            examples.add(new LogisticRegressionTrainer.LabeledExample(featureExtractor.extract(shot), label));
        }
    }

    /** Mean predicted probability vs actual goal rate - should be close to 1.0 if the model is well-calibrated. */
    public double calibrationRatio(LogisticRegressionTrainer trainer, double[] weights, List<LogisticRegressionTrainer.LabeledExample> examples) {
        double totalPredicted = 0.0;
        double totalActual = 0.0;
        for (LogisticRegressionTrainer.LabeledExample example : examples) {
            totalPredicted += trainer.predict(weights, example.features());
            totalActual += example.label();
        }
        return totalPredicted / totalActual;
    }
}
