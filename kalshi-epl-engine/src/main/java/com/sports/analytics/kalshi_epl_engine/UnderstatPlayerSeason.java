package com.sports.analytics.kalshi_epl_engine;

/**
 * One player's season totals from Understat's league table. {@code team} is
 * Understat's team title; a player who moved mid-season has both, comma-separated.
 * xG/xA here are Understat's own figures, used for display only.
 */
public record UnderstatPlayerSeason(String player, String team, String position, int games, int minutes,
                                    int goals, int assists, double xg, double xa) {
}
