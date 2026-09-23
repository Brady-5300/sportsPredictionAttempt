package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validates the model's calibration against real historical match outcomes:
 * when we say "30% chance", does that thing actually happen about 30% of the
 * time? This is a narrower, more honest question than "would this strategy
 * have made money" - answering the money question would require a trustworthy
 * historical market price to compare against, and Kalshi's public trade data
 * for this series turned out not to provide one (every market's entire
 * visible trade history is a short burst that appears to reflect
 * during/after-match information, not genuine pre-match price discovery -
 * confirmed by a market pricing "Tie" at 93% for a match that was, in fact, a
 * tie, which no honest pre-match market would ever do). Calibration doesn't
 * need a market price at all - just our prediction and the real result.
 *
 * Point-in-time correctness: each match's xG ratings only ever use data from
 * BEFORE that match's date (see UnderstatXgProvider.getRatingAsOf) - checking
 * calibration against a model that could see the future wouldn't prove anything.
 *
 * No FotMob lineup/injury adjustment for the same reason as before: we have no
 * historical snapshots of who was injured before a past match.
 */
@Service
public class ModelValidationService {

    private final KalshiHistoricalClient historicalClient;
    private final TickerParserService tickerParserService;
    private final UnderstatXgProvider understatXgProvider;
    private final PoissonModel poissonModel;
    private final KalshiMarketService kalshiMarketService;

    public ModelValidationService(KalshiHistoricalClient historicalClient,
                                   TickerParserService tickerParserService,
                                   UnderstatXgProvider understatXgProvider,
                                   PoissonModel poissonModel,
                                   KalshiMarketService kalshiMarketService) {
        this.historicalClient = historicalClient;
        this.tickerParserService = tickerParserService;
        this.understatXgProvider = understatXgProvider;
        this.poissonModel = poissonModel;
        this.kalshiMarketService = kalshiMarketService;
    }

    public ModelValidationReport run(int maxEvents) {
        List<KalshiEvent> events = historicalClient.fetchSettledEvents(maxEvents);

        int marketsTotal = 0;
        int skippedNoXgData = 0;
        List<PredictionRecord> predictions = new ArrayList<>();

        for (KalshiEvent event : events) {
            String eventTitle = event.getTitle() == null ? "" : event.getTitle();

            for (KalshiMarket market : event.getMarkets()) {
                if (market.getResult() == null || market.getResult().isBlank()) continue;
                marketsTotal++;

                String rawTitle = market.getTitle() == null ? "" : market.getTitle();
                String fullTitle = (rawTitle.toLowerCase().contains("vs") || rawTitle.toLowerCase().contains("beat"))
                    ? rawTitle
                    : eventTitle + ": " + rawTitle;

                Map.Entry<String, String> teams = tickerParserService.extractTeamsFromTitle(fullTitle);
                if (teams == null || teams.getKey() == null || teams.getValue() == null) continue;

                String homeTeam = teams.getKey().replaceAll("(?i)\\s+(wins|is the result)$", "").trim();
                String awayTeam = teams.getValue().replaceAll("(?i)\\s+(wins|is the result)$", "").trim();

                Optional<LocalDate> matchDate = tickerParserService.parseMatchDateFromTicker(market.getTicker());
                if (matchDate.isEmpty()) continue;

                Optional<TeamXgRating> homeRating = understatXgProvider.getRatingAsOf(homeTeam, matchDate.get());
                Optional<TeamXgRating> awayRating = understatXgProvider.getRatingAsOf(awayTeam, matchDate.get());
                if (homeRating.isEmpty() || awayRating.isEmpty()) {
                    skippedNoXgData++;
                    continue;
                }

                double homeXg = XgService.combineAttackDefense(homeRating.get().avgXgFor(), awayRating.get().avgXgAgainst(), true);
                double awayXg = XgService.combineAttackDefense(awayRating.get().avgXgFor(), homeRating.get().avgXgAgainst(), false);

                String marketType = kalshiMarketService.resolveMarketType(market, rawTitle, homeTeam, awayTeam);
                double predictedProb = poissonModel.calculateMarketProbability(homeXg, awayXg, marketType);
                boolean actualOutcome = "yes".equalsIgnoreCase(market.getResult());

                predictions.add(new PredictionRecord(matchDate.get().toString(), fullTitle, marketType, predictedProb, actualOutcome));
            }
        }

        double brier = CalibrationMath.brierScore(predictions);
        double logLoss = CalibrationMath.logLoss(predictions);
        List<CalibrationBucket> buckets = CalibrationMath.buildCalibrationBuckets(predictions);

        return new ModelValidationReport(marketsTotal, skippedNoXgData, predictions.size(),
            CalibrationMath.round4(brier), CalibrationMath.round4(logLoss), buckets, predictions);
    }
}
