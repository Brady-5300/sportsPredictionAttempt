package com.sports.analytics.kalshi_epl_engine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final Path DEFAULT_LOG_FILE = Path.of("data", "prediction-log.jsonl");

    // A bid/ask spread wider than this isn't a meaningful price to compare against.
    static final int MAX_USABLE_SPREAD_CENTS = 10;

    private final KalshiHistoricalClient historicalClient;
    private final Path logFile;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, PredictionLogEntry> entriesByTicker = new LinkedHashMap<>();

    Supplier<Instant> nowSupplier = Instant::now;

    @Autowired
    public PredictionLogService(KalshiHistoricalClient historicalClient) {
        this(historicalClient, DEFAULT_LOG_FILE);
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
     */
    public void recordSnapshot(String ticker, String matchTitle, String marketType, String matchDate,
                               double predictedProbability, Double baseProbability,
                               Integer bidCents, Integer askCents, Optional<Instant> kickoff) {
        Instant now = nowSupplier.get();
        boolean beforeKickoff = kickoff.map(now::isBefore).orElse(false);

        lock.lock();
        try {
            PredictionLogEntry existing = entriesByTicker.get(ticker);
            if (existing == null) {
                if (kickoff.isPresent() && !beforeKickoff) return;
                entriesByTicker.put(ticker, new PredictionLogEntry(
                    ticker, matchTitle, marketType, matchDate, predictedProbability, baseProbability,
                    beforeKickoff ? bidCents : null, beforeKickoff ? askCents : null,
                    kickoff.map(Instant::toString).orElse(null),
                    now.toString(), now.toString(), false, null
                ));
                persist();
                return;
            }

            if (existing.resolved() || !beforeKickoff) return;
            entriesByTicker.put(ticker, existing.withSnapshot(predictedProbability, baseProbability, bidCents, askCents, now.toString()));
            persist();
        } finally {
            lock.unlock();
        }
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
     * Our Brier score vs. the market's, on the same resolved markets (those
     * with a usable pre-kickoff price). The standard error treats each match
     * as one unit, since its home/tie/away markets aren't independent.
     */
    public MarketComparison marketComparison() {
        List<PredictionLogEntry> resolved = resolvedEntries();
        PairedScores scores = pairedScores(resolved, e -> e.marketMidProbability(MAX_USABLE_SPREAD_CENTS));
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
        PairedScores scores = pairedScores(resolved, e -> Optional.ofNullable(e.baseProbability()));
        int adjusted = (int) resolved.stream()
            .filter(e -> e.baseProbability() != null && Math.abs(e.predictedProbability() - e.baseProbability()) > 1e-9)
            .count();
        return new LineupComparison(resolved.size(), scores.compared(), adjusted,
            scores.modelBrier(), scores.otherBrier(), scores.standardError());
    }

    private List<PredictionLogEntry> resolvedEntries() {
        return allEntries().stream().filter(PredictionLogEntry::resolved).toList();
    }

    private record PairedScores(int compared, double modelBrier, double otherBrier, double standardError) {
    }

    /**
     * Brier score of our full-model prediction vs. some other probability,
     * over the resolved entries where the other probability exists. The
     * standard error treats each match as one unit, since its home/tie/away
     * markets aren't independent.
     */
    private PairedScores pairedScores(List<PredictionLogEntry> resolved,
                                      Function<PredictionLogEntry, Optional<Double>> otherProbability) {
        List<PredictionRecord> modelRecords = new ArrayList<>();
        List<PredictionRecord> otherRecords = new ArrayList<>();
        Map<String, Double> diffSumByMatch = new HashMap<>();
        Map<String, Integer> countByMatch = new HashMap<>();

        for (PredictionLogEntry e : resolved) {
            Optional<Double> other = otherProbability.apply(e);
            if (other.isEmpty()) continue;

            modelRecords.add(new PredictionRecord(e.matchDate(), e.matchTitle(), e.marketType(), e.predictedProbability(), e.actualOutcome()));
            otherRecords.add(new PredictionRecord(e.matchDate(), e.matchTitle(), e.marketType(), other.get(), e.actualOutcome()));

            double y = e.actualOutcome() ? 1.0 : 0.0;
            double diff = Math.pow(e.predictedProbability() - y, 2) - Math.pow(other.get() - y, 2);
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
        try {
            for (String line : Files.readAllLines(logFile)) {
                if (line.isBlank()) continue;
                PredictionLogEntry entry = objectMapper.readValue(line, PredictionLogEntry.class);
                entriesByTicker.put(entry.ticker(), entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load prediction log from " + logFile, e);
        }
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
