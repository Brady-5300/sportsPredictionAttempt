package com.sports.analytics.kalshi_epl_engine;

/** A market that couldn't be evaluated - typically because a team has no live xG data (e.g. no slug mapping). */
public record SkippedMarket(String ticker, String title, String reason) {
}
