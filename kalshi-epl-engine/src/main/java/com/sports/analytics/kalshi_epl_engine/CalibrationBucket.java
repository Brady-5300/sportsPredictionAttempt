package com.sports.analytics.kalshi_epl_engine;

/**
 * One bucket of a reliability diagram: among predictions in this probability
 * range, what fraction actually happened? A well-calibrated model has
 * actualFrequency close to avgPredictedProbability in every bucket - e.g.
 * among all "we said ~30%" predictions, the real thing should happen about
 * 30% of the time, not 10% or 60%.
 */
public record CalibrationBucket(String rangeLabel, int count, double avgPredictedProbability, double actualFrequency) {
}
