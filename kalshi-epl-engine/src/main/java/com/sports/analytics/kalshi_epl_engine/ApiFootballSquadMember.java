package com.sports.analytics.kalshi_epl_engine;

/**
 * One player from API-Football's squad listing (football-get-list-player).
 * Injury status is bundled directly into this endpoint's response - there's
 * no separate injuries call on this API.
 */
public record ApiFootballSquadMember(String name, String primaryPosition, boolean injured, String expectedReturn) {

    private static final java.util.Set<String> DEFENSIVE_POSITIONS = java.util.Set.of(
        "GK", "CB", "LB", "RB", "LWB", "RWB", "SW"
    );

    public boolean isDefensivePosition() {
        return primaryPosition != null && DEFENSIVE_POSITIONS.contains(primaryPosition);
    }
}
