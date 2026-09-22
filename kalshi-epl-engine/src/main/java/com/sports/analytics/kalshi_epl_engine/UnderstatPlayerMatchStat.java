package com.sports.analytics.kalshi_epl_engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One player's appearance record for a single match, from Understat's match
 * roster data. Minutes played ("time") is what we actually need this for -
 * per-shot xG contribution is computed from {@link UnderstatShot} (grouped by
 * player name) rather than trusting this record's own "xG" total, for the
 * same reason the rest of this codebase computes its own xG.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnderstatPlayerMatchStat {

    private String player;
    private String player_id;
    private String position;
    private String time;
    private String h_a;

    public String getPlayer() { return player; }
    public void setPlayer(String player) { this.player = player; }

    public String getPlayerId() { return player_id; }
    public void setPlayer_id(String player_id) { this.player_id = player_id; }

    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getHomeOrAway() { return h_a; }
    public void setH_a(String h_a) { this.h_a = h_a; }

    public int minutesPlayed() {
        try {
            return Integer.parseInt(time);
        } catch (Exception e) {
            return 0;
        }
    }

    /** True for goalkeeper/defender positions, based on Understat's position codes. */
    public boolean isDefensivePosition() {
        if (position == null) return false;
        return position.equals("GK") || position.startsWith("D");
    }
}
