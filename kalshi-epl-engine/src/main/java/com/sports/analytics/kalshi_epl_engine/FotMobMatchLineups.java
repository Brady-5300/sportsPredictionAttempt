package com.sports.analytics.kalshi_epl_engine;

import java.util.List;

/**
 * Both sides' lineup/availability data for one match, from a single
 * matchDetails call. Starters are empty until the lineup is confirmed
 * (usually ~1 hour before kickoff); unavailable players are populated well
 * in advance.
 */
public record FotMobMatchLineups(
    List<String> homeStarters,
    List<FotMobUnavailablePlayer> homeUnavailable,
    List<String> awayStarters,
    List<FotMobUnavailablePlayer> awayUnavailable
) {
    public static FotMobMatchLineups empty() {
        return new FotMobMatchLineups(List.of(), List.of(), List.of(), List.of());
    }
}
