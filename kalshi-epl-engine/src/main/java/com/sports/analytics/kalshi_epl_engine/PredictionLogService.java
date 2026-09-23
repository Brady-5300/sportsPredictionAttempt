package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Logs each market's prediction - computed with the FULL live pipeline,
 * including FotMob lineup/injury adjustments - the first time it's evaluated
 * during a live scan, then periodically checks back for settlement to record
 * the real outcome. This is the only way to ever validate the lineup-strength
 * layer, since there's no historical snapshot of who was injured before a
 * past match (see ModelValidationService's javadoc).
 *
 * Persisted as JSONL (one prediction per line) since the project has no
 * database configured; simple to append to, simple to inspect by hand.
 * Kept in memory (keyed by ticker) as the source of truth during a run, with
 * the file rewritten in full on every mutation - the dataset here is at most
 * a few hundred markets a season, so this stays cheap.
 */
@Service
public class PredictionLogService {

    private static final Path LOG_FILE = Path.of("data", "prediction-log.jsonl");

    private final KalshiHistoricalClient historicalClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, PredictionLogEntry> entriesByTicker = new LinkedHashMap<>();

    public PredictionLogService(KalshiHistoricalClient historicalClient) {
        this.historicalClient = historicalClient;
        load();
    }

    /**
     * Logs a prediction for this ticker if (and only if) it hasn't already
     * been logged - a still-active market gets rescanned every 5 minutes by
     * MarketScannerScheduler, and we want exactly one prediction per market,
     * recorded the first time we ever saw it.
     */
    public void logIfNew(String ticker, String matchTitle, String marketType, String matchDate, double predictedProbability) {
        lock.lock();
        try {
            if (entriesByTicker.containsKey(ticker)) return;

            PredictionLogEntry entry = new PredictionLogEntry(
                ticker, matchTitle, marketType, matchDate, predictedProbability,
                Instant.now().toString(), false, null
            );
            entriesByTicker.put(ticker, entry);
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
        if (unresolved.isEmpty()) return 0;

        int resolvedCount = 0;
        for (PredictionLogEntry entry : unresolved) {
            Optional<KalshiMarket> market = historicalClient.fetchSingleMarket(entry.ticker());
            if (market.isEmpty()) continue;

            String result = market.get().getResult();
            if (result == null || result.isBlank()) continue;

            boolean actualOutcome = "yes".equalsIgnoreCase(result);
            lock.lock();
            try {
                entriesByTicker.put(entry.ticker(), entry.withResolution(actualOutcome));
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
        List<PredictionRecord> resolved = allEntries().stream()
            .filter(PredictionLogEntry::resolved)
            .map(e -> new PredictionRecord(e.matchDate(), e.matchTitle(), e.marketType(), e.predictedProbability(), e.actualOutcome()))
            .toList();

        double brier = CalibrationMath.brierScore(resolved);
        double logLoss = CalibrationMath.logLoss(resolved);
        List<CalibrationBucket> buckets = CalibrationMath.buildCalibrationBuckets(resolved);

        int total = allEntries().size();
        return new ModelValidationReport(total, 0, resolved.size(), CalibrationMath.round4(brier), CalibrationMath.round4(logLoss), buckets, resolved);
    }

    private void load() {
        if (!Files.exists(LOG_FILE)) return;
        try {
            for (String line : Files.readAllLines(LOG_FILE)) {
                if (line.isBlank()) continue;
                PredictionLogEntry entry = objectMapper.readValue(line, PredictionLogEntry.class);
                entriesByTicker.put(entry.ticker(), entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load prediction log from " + LOG_FILE, e);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(LOG_FILE.getParent());
            List<String> lines = new ArrayList<>();
            for (PredictionLogEntry entry : entriesByTicker.values()) {
                lines.add(objectMapper.writeValueAsString(entry));
            }
            Files.write(LOG_FILE, lines);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist prediction log to " + LOG_FILE, e);
        }
    }
}
