package com.sports.analytics.kalshi_epl_engine;

/** A player FotMob currently lists as unavailable for a specific match (injury/suspension/etc). */
public record FotMobUnavailablePlayer(String name, String type, String expectedReturn) {
}
