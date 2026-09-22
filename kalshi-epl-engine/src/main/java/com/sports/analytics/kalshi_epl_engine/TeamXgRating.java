package com.sports.analytics.kalshi_epl_engine;

/**
 * A team's rolling-average expected goals for and against, computed from real
 * shot data over its most recent completed matches.
 */
public record TeamXgRating(double avgXgFor, double avgXgAgainst, int matchesUsed) {
}
