package com.sports.analytics.kalshi_epl_engine;

import java.util.List;

public record ModelValidationReport(
    int marketsTotal,
    int skippedNoXgData,
    int predictionsEvaluated,
    double brierScore,
    double logLoss,
    List<CalibrationBucket> calibrationBuckets,
    List<PredictionRecord> predictions
) {
}
