package com.sports.analytics.kalshi_epl_engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Offline tool to calibrate XgService's match-level xG formula against real
 * Understat results. Fits a Poisson regression (log-link) on
 * (log own attack rating, log opponent defense rating, home/away) ->
 * actual goals scored, using the SAME point-in-time rating logic as the live
 * app (UnderstatXgProvider.getRatingAsOf) so the fit reflects exactly what the
 * live model would have known before each match, not lookahead-biased data.
 *
 * Not part of the live application - run manually (see MatchXgModelCalibrationTest)
 * whenever recalibration is wanted, then paste the fitted coefficients into
 * XgService.
 */
public class MatchXgModelCalibrator {

    /** Feature order: [bias, log(ownAttack), log(opponentDefense), isHome]. */
    public static final int FEATURE_COUNT = 4;

    private final UnderstatScraperService scraper;
    private final UnderstatXgProvider understatXgProvider;
    private final TeamNameResolver teamNameResolver;

    public MatchXgModelCalibrator(UnderstatScraperService scraper, UnderstatXgProvider understatXgProvider,
                                   TeamNameResolver teamNameResolver) {
        this.scraper = scraper;
        this.understatXgProvider = understatXgProvider;
        this.teamNameResolver = teamNameResolver;
    }

    public record Dataset(List<PoissonRegressionTrainer.Example> examples, int matchesUsed, int matchesSkippedNoRating) {
    }

    /**
     * Collects training examples from each given team's full season schedule.
     * Each completed match contributes ONE example: (this team's point-in-time
     * attack rating, the opponent's point-in-time defense rating, whether this
     * team was home) -> this team's actual goals scored. A single real match
     * between two teams in the list contributes two independent examples (one
     * from each side's perspective) - not a duplicate, since attack and
     * defense are asymmetric.
     */
    public Dataset collectDataset(List<String> teamNames, int season) {
        List<PoissonRegressionTrainer.Example> examples = new ArrayList<>();
        int matchesUsed = 0;
        int skipped = 0;

        for (String teamName : teamNames) {
            String slug = teamNameResolver.getUnderstatSlug(teamName);
            if (slug == null) continue;

            List<UnderstatTeamMatch> matches = scraper.fetchTeamMatches(slug, season);
            for (UnderstatTeamMatch match : matches) {
                if (!match.isResult()) continue;
                Integer ownGoals = match.getOwnGoals();
                if (ownGoals == null) continue;

                String opponentName = "h".equals(match.getSide()) ? match.getAwayTeamTitle() : match.getHomeTeamTitle();
                if (opponentName == null) continue;

                LocalDate matchDate = parseDate(match.getDatetime());
                if (matchDate == null) continue;

                Optional<TeamXgRating> ownRating = understatXgProvider.getRatingAsOf(teamName, matchDate);
                Optional<TeamXgRating> opponentRating = understatXgProvider.getRatingAsOf(opponentName, matchDate);
                if (ownRating.isEmpty() || opponentRating.isEmpty()) {
                    skipped++;
                    continue;
                }

                boolean isHome = "h".equals(match.getSide());
                double[] features = {
                    1.0,
                    Math.log(Math.max(ownRating.get().avgXgFor(), 0.05)),
                    Math.log(Math.max(opponentRating.get().avgXgAgainst(), 0.05)),
                    isHome ? 1.0 : 0.0
                };
                examples.add(new PoissonRegressionTrainer.Example(features, ownGoals));
                matchesUsed++;
            }
        }

        return new Dataset(examples, matchesUsed, skipped);
    }

    private LocalDate parseDate(String datetime) {
        if (datetime == null || datetime.length() < 10) return null;
        try {
            return LocalDate.parse(datetime.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    /** Mean predicted goals vs mean actual goals - should be close to 1.0 if well-calibrated. */
    public double calibrationRatio(PoissonRegressionTrainer trainer, double[] weights, List<PoissonRegressionTrainer.Example> examples) {
        double totalPredicted = 0.0;
        double totalActual = 0.0;
        for (PoissonRegressionTrainer.Example example : examples) {
            totalPredicted += trainer.predict(weights, example.features());
            totalActual += example.actualCount();
        }
        return totalPredicted / totalActual;
    }
}
