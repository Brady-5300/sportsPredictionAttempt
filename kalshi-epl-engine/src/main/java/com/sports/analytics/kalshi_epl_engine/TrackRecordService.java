package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the live prediction log into a track record: per-match results,
 * a weekend-by-weekend model-vs-Kalshi scoreboard, calibration, and how the
 * app's YES picks would have done.
 */
@Service
public class TrackRecordService {

    // Same rule the scanner uses for "YES (Undervalued)": edge after the ask and fee above 3 points.
    private static final double PICK_EDGE_THRESHOLD = 0.03;

    private final PredictionLogService predictionLogService;
    private final PoissonModel poissonModel;

    public TrackRecordService(PredictionLogService predictionLogService, PoissonModel poissonModel) {
        this.predictionLogService = predictionLogService;
        this.poissonModel = poissonModel;
    }

    public TrackRecord build(ZoneId zone) {
        Map<String, List<PredictionLogEntry>> byMatch = new LinkedHashMap<>();
        for (PredictionLogEntry e : predictionLogService.allEntries()) {
            if (e.resolved() && e.baseProbability() != null) {
                byMatch.computeIfAbsent(matchKey(e.ticker()), k -> new ArrayList<>()).add(e);
            }
        }

        List<TrackRecord.Match> matches = new ArrayList<>();
        List<PredictionRecord> calibrationRecords = new ArrayList<>();
        int picks = 0, wins = 0;
        double staked = 0.0, returned = 0.0;
        Map<LocalDate, double[]> weekSums = new LinkedHashMap<>(); // weekend -> {modelSum, kalshiSum, markets, matches}

        for (List<PredictionLogEntry> entries : byMatch.values()) {
            PredictionLogEntry first = entries.get(0);
            List<TrackRecord.Market> markets = new ArrayList<>();
            String result = null;
            double modelSum = 0.0, kalshiSum = 0.0;
            int compared = 0;

            for (PredictionLogEntry e : entries) {
                double model = e.baseProbability();
                Optional<Double> kalshi = e.marketMidProbability(PredictionLogService.MAX_USABLE_SPREAD_CENTS);
                boolean happened = Boolean.TRUE.equals(e.actualOutcome());
                boolean pick = isPick(e);

                calibrationRecords.add(new PredictionRecord(e.matchDate(), e.matchTitle(), e.marketType(), model, happened));
                if (happened) result = outcomeLabel(e);
                if (kalshi.isPresent()) {
                    double y = happened ? 1.0 : 0.0;
                    modelSum += (model - y) * (model - y);
                    kalshiSum += (kalshi.get() - y) * (kalshi.get() - y);
                    compared++;
                }
                if (pick) {
                    int costCents = e.marketAskCents() + poissonModel.calculateKalshiFeeCents(e.marketAskCents());
                    picks++;
                    staked += 1.0;
                    if (happened) {
                        wins++;
                        returned += 100.0 / costCents;
                    }
                }
                markets.add(new TrackRecord.Market(outcomeLabel(e), round4(model), kalshi.map(TrackRecordService::round4).orElse(null), happened, pick));
            }

            Double modelBrier = compared == 0 ? null : round4(modelSum / compared);
            Double kalshiBrier = compared == 0 ? null : round4(kalshiSum / compared);
            String kickoff = first.kickoff() != null ? first.kickoff() : first.matchDate();
            matches.add(new TrackRecord.Match(matchName(first), kickoff, result, markets, modelBrier, kalshiBrier,
                compared == 0 ? null : winner(modelBrier, kalshiBrier)));

            if (compared > 0) {
                double[] sums = weekSums.computeIfAbsent(weekendEnding(kickoff, zone), k -> new double[4]);
                sums[0] += modelSum;
                sums[1] += kalshiSum;
                sums[2] += compared;
                sums[3] += 1;
            }
        }

        matches.sort(Comparator.comparing(TrackRecord.Match::kickoff).reversed());

        List<TrackRecord.Week> weeks = new ArrayList<>();
        int modelWeeks = 0, kalshiWeeks = 0, tiedWeeks = 0;
        for (Map.Entry<LocalDate, double[]> w : weekSums.entrySet()) {
            double[] s = w.getValue();
            double modelBrier = round4(s[0] / s[2]);
            double kalshiBrier = round4(s[1] / s[2]);
            String winner = winner(modelBrier, kalshiBrier);
            switch (winner) {
                case "model" -> modelWeeks++;
                case "kalshi" -> kalshiWeeks++;
                default -> tiedWeeks++;
            }
            weeks.add(new TrackRecord.Week(w.getKey().toString(), (int) s[3], modelBrier, kalshiBrier, winner));
        }
        weeks.sort(Comparator.comparing(TrackRecord.Week::weekendEnding).reversed());

        return new TrackRecord(matches.size(), predictionLogService.marketComparison(),
            CalibrationMath.buildCalibrationBuckets(calibrationRecords),
            new TrackRecord.PickRecord(picks, wins, round2(staked), round2(returned), round2(returned - staked)),
            matches, weeks, modelWeeks, kalshiWeeks, tiedWeeks);
    }

    private boolean isPick(PredictionLogEntry e) {
        Integer ask = e.marketAskCents();
        if (ask == null || ask <= 0 || ask >= 100) return false;
        double cost = (ask + poissonModel.calculateKalshiFeeCents(ask)) / 100.0;
        return e.baseProbability() - cost > PICK_EDGE_THRESHOLD;
    }

    /** Lower Brier wins; differences under 0.0005 count as a tie. */
    private static String winner(double model, double kalshi) {
        if (Math.abs(model - kalshi) < 0.0005) return "tie";
        return model < kalshi ? "model" : "kalshi";
    }

    /** Weekends run Tuesday through Monday, matching how EPL rounds are scheduled. */
    static LocalDate weekendEnding(String kickoffOrDate, ZoneId zone) {
        LocalDate date = kickoffOrDate.length() > 10
            ? Instant.parse(kickoffOrDate).atZone(zone).toLocalDate()
            : LocalDate.parse(kickoffOrDate);
        return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    /** "Arsenal vs Leeds United: Leeds United wins" -> "Arsenal vs Leeds United". */
    static String matchName(PredictionLogEntry e) {
        String title = e.matchTitle() == null ? "" : e.matchTitle();
        int colon = title.indexOf(':');
        String name = colon >= 0 ? title.substring(0, colon) : title;
        return name.replaceAll("(?i)\\s+winner\\??$", "").trim();
    }

    /** "Arsenal vs Leeds United: Leeds United wins" -> "Leeds United wins"; falls back to the market type. */
    static String outcomeLabel(PredictionLogEntry e) {
        String title = e.matchTitle() == null ? "" : e.matchTitle();
        int colon = title.indexOf(':');
        if (colon >= 0 && colon < title.length() - 1) return title.substring(colon + 1).trim();
        String[] teams = matchName(e).split("\\s+vs\\.?\\s+");
        return switch (e.marketType() == null ? "" : e.marketType()) {
            case "HOME" -> (teams.length == 2 ? teams[0] : "Home") + " wins";
            case "AWAY" -> (teams.length == 2 ? teams[1] : "Away") + " wins";
            default -> "Tie";
        };
    }

    private static String matchKey(String ticker) {
        int lastDash = ticker.lastIndexOf('-');
        return lastDash > 0 ? ticker.substring(0, lastDash) : ticker;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
