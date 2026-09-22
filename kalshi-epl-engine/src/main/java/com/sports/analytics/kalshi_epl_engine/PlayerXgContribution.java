package com.sports.analytics.kalshi_epl_engine;

/**
 * A player's total xG output and minutes played across a team's rolling
 * window of recent matches - the basis for estimating how much a missing
 * player's absence should dent the team's attack rating.
 */
public record PlayerXgContribution(String playerName, boolean defensivePosition, double totalXg, int totalMinutes) {

    /** Expected xG contribution per 90 minutes, based on their recent minutes. */
    public double per90Xg() {
        if (totalMinutes <= 0) return 0.0;
        return (totalXg / totalMinutes) * 90.0;
    }
}
