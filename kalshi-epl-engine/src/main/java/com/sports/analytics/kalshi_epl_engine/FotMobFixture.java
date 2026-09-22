package com.sports.analytics.kalshi_epl_engine;

import java.time.LocalDate;

/** One fixture from a team's FotMob schedule, used to find a specific match's id by date. */
public record FotMobFixture(int matchId, int leagueId, LocalDate matchDate, boolean finished) {
}
