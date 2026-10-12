package com.sports.analytics.kalshi_epl_engine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Records each market's prediction from live scans, alongside Kalshi's
 * bid/ask at the same moment, then checks back for settlement to record the
 * real outcome. Two questions this answers over time:
 * 1. Is the full model (including FotMob lineup adjustments) well calibrated?
 * 2. Does it beat the market's own price? If not, there's no real edge.
 *
 * Each entry is refreshed on every scan until kickoff, then frozen - so we
 * keep our LAST pre-kickoff view (after lineups are posted, ~1h before) and
 * the market's price at that same moment. Kalshi markets keep trading
 * in-play, so anything captured after kickoff would give the market
 * information our model doesn't have.
 *
 * Persisted as JSONL (one entry per line) since the project has no database.
 * Kept in memory keyed by ticker, with the file rewritten on each change -
 * at most a few hundred markets a season, so this stays cheap.
 */
@Service
public class PredictionLogService {

    // A bid/ask spread wider than this isn't a meaningful price to compare against.
    static final int MAX_USABLE_SPREAD_CENTS = 10;

    // Confirmed lineups come out about 60-75 minutes before kickoff. A "lineups seen"
    // snapshot from earlier than this can't be a real confirmed lineup (FotMob also
    // publishes guessed ones days ahead), so it's never recorded - and an old one
    // is discarded on startup.
    private static final Duration LINEUP_WINDOW = Duration.ofHours(2);

    // Lineup-speed test. The price from the scan before lineups were seen only
    // counts if that scan was recent; after lineups, Kalshi's price is kept at
    // these checkpoints (minutes), each from the first scan that reaches it.
    private static final Duration BEFORE_LINEUPS_MAX_AGE = Duration.ofMinutes(15);
    static final int[] PRICE_CHECKPOINTS_MINUTES = {5, 10, 15, 20, 30, 45, 60};
    private static final int CHECKPOINT_TOLERANCE_MINUTES = 5;

    // How far our lineup adjustment must move a probability to count as a trade
    // signal, and how many such matches the test needs before giving a verdict.
    // Both were fixed before any data came in, so they can't be tuned to the result.
    static final double TRADE_SIGNAL = 0.02;
    static final int SPEED_TEST_TARGET_MATCHES = 40;

    private static boolean inLineupWindow(Instant seenAt, Instant kickoff) {
        return !seenAt.isBefore(kickoff.minus(LINEUP_WINDOW));
    }

    private final KalshiHistoricalClient historicalClient;
    private final Path logFile;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, PredictionLogEntry> entriesByTicker = new LinkedHashMap<>();

    Supplier<Instant> nowSupplier = Instant::now;

    @Autowired
    public PredictionLogService(KalshiHistoricalClient historicalClient,
                                @Value("${prediction-log.path:data/prediction-log.jsonl}") String logFile) {
        this(historicalClient, Path.of(logFile));
    }

    PredictionLogService(KalshiHistoricalClient historicalClient, Path logFile) {
        this.historicalClient = historicalClient;
        this.logFile = logFile;
        load();
    }

    /**
     * Records (or refreshes) this market's pre-kickoff snapshot.
     *
     * - First time seen before kickoff: logged.
     * - Seen again before kickoff: prediction and price refreshed.
     * - At or after kickoff: nothing changes (and a market first seen
     *   mid-match isn't logged at all).
     * - Kickoff unknown: logged once on first sighting and never refreshed,
     *   with no market price, since we can't tell a pre-match price from an
     *   in-play one.
     * - The first pre-kickoff scan with confirmed lineups also stores an
     *   "at lineups" copy of the snapshot (see PredictionLogEntry).
     */
    public void recordSnapshot(String ticker, String matchTitle, String marketType, String matchDate,
                               double predictedProbability, Double baseProbability,
                               Integer bidCents, Integer askCents, Optional<Instant> kickoff,
                               boolean lineupsConfirmed) {
        Instant now = nowSupplier.get();
        boolean beforeKickoff = kickoff.map(now::isBefore).orElse(false);

        lock.lock();
        try {
            PredictionLogEntry existing = entriesByTicker.get(ticker);
            PredictionLogEntry updated;
            if (existing == null) {
                if (kickoff.isPresent() && !beforeKickoff) return;
                updated = new PredictionLogEntry(
                    ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
                    beforeKickoff ? bidCents : null, beforeKickoff ? askCents : null,
                    kickoff.map(Instant::toString).orElse(null),
                    now.toString(), now.toString(), false, null,
                    null, null, null, null, null
                );
            } else {
                if (existing.resolved() || !beforeKickoff) return;
                updated = existing.withSnapshot(predictedProbability, baseProbability, bidCents, askCents, now.toString());
            }

            if (beforeKickoff && lineupsConfirmed && updated.lineupsSeenAt() == null && inLineupWindow(now, kickoff.get())) {
                boolean recentBefore = existing != null && existing.snapshotAt() != null
                    && !Instant.parse(existing.snapshotAt()).isBefore(now.minus(BEFORE_LINEUPS_MAX_AGE));
                updated = recentBefore
                    ? updated.withLineupSnapshot(existing.marketBidCents(), existing.marketAskCents(), existing.snapshotAt())
                    : updated.withLineupSnapshot(null, null, null);
            } else if (beforeKickoff && existing != null && existing.lineupsSeenAt() != null) {
                updated = withDuePriceCheck(updated, now, bidCents, askCents);
            }
            entriesByTicker.put(ticker, updated);
            persist();
        } finally {
            lock.unlock();
        }
    }

    /** Adds the checkpoint this scan reaches, unless it's already taken or this scan is too late for it. */
    private static PredictionLogEntry withDuePriceCheck(PredictionLogEntry entry, Instant now, Integer bid, Integer ask) {
        long minutes = Duration.between(Instant.parse(entry.lineupsSeenAt()), now).toMinutes();
        for (int checkpoint : PRICE_CHECKPOINTS_MINUTES) {
            boolean taken = entry.pricesAfterLineups().stream().anyMatch(c -> c.minutesAfterLineups() == checkpoint);
            if (!taken && minutes >= checkpoint && minutes < checkpoint + CHECKPOINT_TOLERANCE_MINUTES) {
                return entry.withPriceCheck(checkpoint, bid, ask);
            }
        }
        return entry;
    }

    /**
     * True while some logged, unresolved match kicks off within {@code window}
     * of now - when lineups are due or just out, so it's worth scanning often.
     */
    public boolean hasMatchKickingOffWithin(Duration window) {
        Instant now = nowSupplier.get();
        return allEntries().stream()
            .filter(e -> !e.resolved() && e.kickoff() != null)
            .map(e -> Instant.parse(e.kickoff()))
            .anyMatch(k -> now.isBefore(k) && !k.isAfter(now.plus(window)));
    }

    /**
     * Checks every still-unresolved logged prediction against Kalshi's
     * single-market endpoint and records the real outcome for any that have
     * settled. Returns how many were newly resolved this pass.
     */
    public int checkForResolutions() {
        List<PredictionLogEntry> unresolved;
        lock.lock();
        try {
            unresolved = entriesByTicker.values().stream().filter(e -> !e.resolved()).toList();
        } finally {
            lock.unlock();
        }

        int resolvedCount = 0;
        for (PredictionLogEntry entry : unresolved) {
            Optional<KalshiMarket> market = historicalClient.fetchSingleMarket(entry.ticker());
            if (market.isEmpty()) continue;

            String result = market.get().getResult();
            if (result == null || result.isBlank()) continue;

            boolean actualOutcome = "yes".equalsIgnoreCase(result);
            lock.lock();
            try {
                entriesByTicker.put(entry.ticker(), entriesByTicker.get(entry.ticker()).withResolution(actualOutcome));
            } finally {
                lock.unlock();
            }
            resolvedCount++;
        }

        if (resolvedCount > 0) {
            lock.lock();
            try {
                persist();
            } finally {
                lock.unlock();
            }
        }
        return resolvedCount;
    }

    public List<PredictionLogEntry> allEntries() {
        lock.lock();
        try {
            return List.copyOf(entriesByTicker.values());
        } finally {
            lock.unlock();
        }
    }

    /** Calibration stats over only the predictions that have resolved so far. */
    public ModelValidationReport calibrationReport() {
        List<PredictionLogEntry> entries = allEntries();
        List<PredictionRecord> resolved = entries.stream()
            .filter(PredictionLogEntry::resolved)
            .map(e -> new PredictionRecord(e.matchDate(), e.matchTitle(), e.marketType(), e.predictedProbability(), e.actualOutcome()))
            .toList();

        return new ModelValidationReport(entries.size(), 0, resolved.size(),
            CalibrationMath.round4(CalibrationMath.brierScore(resolved)),
            CalibrationMath.round4(CalibrationMath.logLoss(resolved)),
            CalibrationMath.buildCalibrationBuckets(resolved), resolved);
    }

    /**
     * The model we bet with (the base model, no lineup adjustment) vs. the
     * market's price, on the same resolved markets with a usable pre-kickoff
     * price.
     */
    public MarketComparison marketComparison() {
        List<PredictionLogEntry> resolved = resolvedEntries();
        PairedScores scores = pairedScores(resolved,
            e -> Optional.ofNullable(e.baseProbability()),
            e -> e.marketMidProbability(MAX_USABLE_SPREAD_CENTS));
        return new MarketComparison(resolved.size(), scores.compared(),
            scores.modelBrier(), scores.otherBrier(), scores.standardError());
    }

    /**
     * Full model (with lineup adjustments) vs. the same model without them,
     * on the same resolved markets - the direct test of whether the lineup
     * layer helps.
     */
    public LineupComparison lineupComparison() {
        List<PredictionLogEntry> resolved = resolvedEntries();
        PairedScores scores = pairedScores(resolved,
            e -> Optional.of(e.predictedProbability()),
            e -> Optional.ofNullable(e.baseProbability()));
        int adjusted = (int) resolved.stream()
            .filter(e -> e.baseProbability() != null && Math.abs(e.predictedProbability() - e.baseProbability()) > 1e-9)
            .count();
        return new LineupComparison(resolved.size(), scores.compared(), adjusted,
            scores.modelBrier(), scores.otherBrier(), scores.standardError());
    }

    /**
     * Did Kalshi's price keep moving after lineups appeared, in the direction
     * our lineup adjustment pointed? Doesn't need results - only the price at
     * lineups and the last pre-kickoff price, so it fills in as soon as
     * matches kick off.
     */
    public LineupSpeedReport lineupSpeedReport() {
        Instant now = nowSupplier.get();
        int withBothPrices = 0;
        double absoluteMoveSum = 0.0;
        int towardCount = 0;
        int n = 0;
        Map<String, Double> towardSumByMatch = new HashMap<>();
        Map<String, Integer> countByMatch = new HashMap<>();

        for (PredictionLogEntry e : allEntries()) {
            if (e.lineupsSeenAt() == null || e.kickoff() == null || now.isBefore(Instant.parse(e.kickoff()))) continue;
            if (!Instant.parse(e.snapshotAt()).isAfter(Instant.parse(e.lineupsSeenAt()))) continue;
            Optional<Double> atLineups = e.marketMidProbabilityAtLineups(MAX_USABLE_SPREAD_CENTS);
            Optional<Double> atKickoff = e.marketMidProbability(MAX_USABLE_SPREAD_CENTS);
            if (atLineups.isEmpty() || atKickoff.isEmpty()) continue;

            double moveCents = (atKickoff.get() - atLineups.get()) * 100;
            withBothPrices++;
            absoluteMoveSum += Math.abs(moveCents);

            if (e.predictedProbabilityAtLineups() == null || e.baseProbabilityAtLineups() == null) continue;
            double signal = e.predictedProbabilityAtLineups() - e.baseProbabilityAtLineups();
            if (Math.abs(signal) < 0.01) continue;

            double toward = Math.signum(signal) * moveCents;
            if (toward > 0) towardCount++;
            n++;
            String match = matchKey(e.ticker());
            towardSumByMatch.merge(match, toward, Double::sum);
            countByMatch.merge(match, 1, Integer::sum);
        }

        double avgToward = n == 0 ? 0.0 : towardSumByMatch.values().stream().mapToDouble(Double::doubleValue).sum() / n;

        // ---- trade test: markets where our lineup read moved the probability by TRADE_SIGNAL or more
        Clustered before = new Clustered();
        Clustered flip = new Clustered();
        Clustered hold = new Clustered();
        Map<Integer, Clustered> checkpoints = new LinkedHashMap<>();
        for (int c : PRICE_CHECKPOINTS_MINUTES) checkpoints.put(c, new Clustered());
        Set<String> signalMatches = new HashSet<>();

        for (PredictionLogEntry e : allEntries()) {
            if (e.lineupsSeenAt() == null || e.kickoff() == null || now.isBefore(Instant.parse(e.kickoff()))) continue;
            if (e.predictedProbabilityAtLineups() == null || e.baseProbabilityAtLineups() == null) continue;
            double signal = e.predictedProbabilityAtLineups() - e.baseProbabilityAtLineups();
            if (Math.abs(signal) < TRADE_SIGNAL) continue;
            Optional<Double> atLineups = e.marketMidProbabilityAtLineups(MAX_USABLE_SPREAD_CENTS);
            if (atLineups.isEmpty()) continue;
            String match = matchKey(e.ticker());
            signalMatches.add(match);
            double sign = Math.signum(signal);

            e.marketMidProbabilityBeforeLineups(MAX_USABLE_SPREAD_CENTS)
                .ifPresent(b -> before.add(match, sign * (atLineups.get() - b) * 100));
            for (PredictionLogEntry.PriceCheck check : e.pricesAfterLineups()) {
                Clustered bucket = checkpoints.get(check.minutesAfterLineups());
                if (bucket == null) continue;
                PredictionLogEntry.midpoint(check.bidCents(), check.askCents(), MAX_USABLE_SPREAD_CENTS)
                    .ifPresent(p -> bucket.add(match, sign * (p - atLineups.get()) * 100));
            }

            // Buy YES (signal up) or NO (signal down) at the lineup-time ask, paying Kalshi's fee.
            // A NO contract costs 100 minus the YES bid.
            int buy = sign > 0 ? e.marketAskCentsAtLineups() : 100 - e.marketBidCentsAtLineups();
            int buyFee = PoissonModel.kalshiFeeCents(buy);
            // Flip: sell at the last pre-kickoff bid, paying the fee again.
            if (e.marketMidProbability(MAX_USABLE_SPREAD_CENTS).isPresent()
                    && Instant.parse(e.snapshotAt()).isAfter(Instant.parse(e.lineupsSeenAt()))) {
                int sell = sign > 0 ? e.marketBidCents() : 100 - e.marketAskCents();
                flip.add(match, sell - buy - buyFee - PoissonModel.kalshiFeeCents(sell));
            }
            // Hold: keep the contract until Kalshi settles it.
            if (e.resolved()) {
                boolean wins = sign > 0 ? e.actualOutcome() : !e.actualOutcome();
                hold.add(match, (wins ? 100 : 0) - buy - buyFee);
            }
        }

        List<LineupSpeedReport.Checkpoint> path = new ArrayList<>();
        checkpoints.forEach((minutes, c) -> {
            if (c.n > 0) path.add(new LineupSpeedReport.Checkpoint(minutes, c.n, c.mean(), c.se()));
        });
        return new LineupSpeedReport(withBothPrices, n,
            CalibrationMath.round4(avgToward),
            CalibrationMath.round4(clusteredStandardError(towardSumByMatch, countByMatch, n)),
            n == 0 ? 0.0 : CalibrationMath.round4((double) towardCount / n),
            withBothPrices == 0 ? 0.0 : CalibrationMath.round4(absoluteMoveSum / withBothPrices),
            signalMatches.size(), SPEED_TEST_TARGET_MATCHES,
            before.n, before.mean(), before.se(),
            path, flip.toTrade(), hold.toTrade(),
            speedVerdict(signalMatches.size(), flip, hold));
    }

    /** The decision rule, fixed in advance: a trade must make money after fees with its 95% range above zero. */
    private static String speedVerdict(int matches, Clustered flip, Clustered hold) {
        if (matches < SPEED_TEST_TARGET_MATCHES) {
            return "Collecting data: " + matches + " of " + SPEED_TEST_TARGET_MATCHES + " matches with a lineup signal so far.";
        }
        boolean flipWins = flip.n > 0 && flip.mean() - 2 * flip.se() > 0;
        boolean holdWins = hold.n > 0 && hold.mean() - 2 * hold.se() > 0;
        if (flipWins || holdWins) {
            return "Speed edge found: trading on lineups made money after Kalshi's fees"
                + (flipWins ? " (buying at lineups, selling at kickoff)" : " (buying at lineups and holding)")
                + ", with the 95% range above zero.";
        }
        return "No tradable speed edge: after Kalshi's fees and spread, trading on lineups didn't reliably make money.";
    }

    /** Per-market values grouped by match, for an average with a match-clustered standard error. */
    private static final class Clustered {
        final Map<String, Double> sumByMatch = new HashMap<>();
        final Map<String, Integer> countByMatch = new HashMap<>();
        int n;
        double total;

        void add(String match, double value) {
            sumByMatch.merge(match, value, Double::sum);
            countByMatch.merge(match, 1, Integer::sum);
            n++;
            total += value;
        }

        double mean() {
            return n == 0 ? 0.0 : CalibrationMath.round4(total / n);
        }

        double se() {
            return CalibrationMath.round4(clusteredStandardError(sumByMatch, countByMatch, n));
        }

        LineupSpeedReport.Trade toTrade() {
            return new LineupSpeedReport.Trade(n, mean(), se(), CalibrationMath.round4(total));
        }
    }

    private List<PredictionLogEntry> resolvedEntries() {
        return allEntries().stream().filter(PredictionLogEntry::resolved).toList();
    }

    private record PairedScores(int compared, double modelBrier, double otherBrier, double standardError) {
    }

    /**
     * Brier score of one probability vs. another, over the resolved entries
     * where both exist. The standard error treats each match as one unit,
     * since its home/tie/away markets aren't independent.
     */
    private PairedScores pairedScores(List<PredictionLogEntry> resolved,
                                      Function<PredictionLogEntry, Optional<Double>> modelProbability,
                                      Function<PredictionLogEntry, Optional<Double>> otherProbability) {
        List<PredictionRecord> modelRecords = new ArrayList<>();
        List<PredictionRecord> otherRecords = new ArrayList<>();
        Map<String, Double> diffSumByMatch = new HashMap<>();
        Map<String, Integer> countByMatch = new HashMap<>();

        for (PredictionLogEntry e : resolved) {
            Optional<Double> model = modelProbability.apply(e);
            Optional<Double> other = otherProbability.apply(e);
            if (model.isEmpty() || other.isEmpty()) continue;

            modelRecords.add(new PredictionRecord(e.matchDate(), e.matchTitle(), e.marketType(), model.get(), e.actualOutcome()));
            otherRecords.add(new PredictionRecord(e.matchDate(), e.matchTitle(), e.marketType(), other.get(), e.actualOutcome()));

            double y = e.actualOutcome() ? 1.0 : 0.0;
            double diff = Math.pow(model.get() - y, 2) - Math.pow(other.get() - y, 2);
            String match = matchKey(e.ticker());
            diffSumByMatch.merge(match, diff, Double::sum);
            countByMatch.merge(match, 1, Integer::sum);
        }

        int n = modelRecords.size();
        return new PairedScores(n,
            CalibrationMath.round4(CalibrationMath.brierScore(modelRecords)),
            CalibrationMath.round4(CalibrationMath.brierScore(otherRecords)),
            CalibrationMath.round4(clusteredStandardError(diffSumByMatch, countByMatch, n)));
    }

    /** "KXEPLGAME-26SEP20BOULFC-LFC" -> "KXEPLGAME-26SEP20BOULFC". */
    private static String matchKey(String ticker) {
        int lastDash = ticker.lastIndexOf('-');
        return lastDash > 0 ? ticker.substring(0, lastDash) : ticker;
    }

    private static double clusteredStandardError(Map<String, Double> diffSumByMatch, Map<String, Integer> countByMatch, int n) {
        int matches = diffSumByMatch.size();
        if (matches < 2) return 0.0;

        double meanDiff = diffSumByMatch.values().stream().mapToDouble(Double::doubleValue).sum() / n;
        double sumSquares = 0.0;
        for (Map.Entry<String, Double> match : diffSumByMatch.entrySet()) {
            double residual = match.getValue() - countByMatch.get(match.getKey()) * meanDiff;
            sumSquares += residual * residual;
        }
        double variance = sumSquares / ((double) n * n) * matches / (matches - 1.0);
        return Math.sqrt(variance);
    }

    private void load() {
        if (!Files.exists(logFile)) return;
        boolean repaired = false;
        try {
            for (String line : Files.readAllLines(logFile)) {
                if (line.isBlank()) continue;
                PredictionLogEntry entry = objectMapper.readValue(line, PredictionLogEntry.class);
                if (entry.lineupsSeenAt() != null && entry.kickoff() != null
                        && !inLineupWindow(Instant.parse(entry.lineupsSeenAt()), Instant.parse(entry.kickoff()))) {
                    entry = entry.withoutLineupSnapshot();
                    repaired = true;
                }
                entriesByTicker.put(entry.ticker(), entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load prediction log from " + logFile, e);
        }
        if (repaired) persist();
    }

    private void persist() {
        try {
            if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
            List<String> lines = new ArrayList<>();
            for (PredictionLogEntry entry : entriesByTicker.values()) {
                lines.add(objectMapper.writeValueAsString(entry));
            }
            Files.write(logFile, lines);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist prediction log to " + logFile, e);
        }
    }
}
