package com.sports.analytics.kalshi_epl_engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class UnderstatShot {

    private String id;
    private String minute;
    private String result;

    // Understat encodes pitch position as normalized [0,1] strings, with the
    // attacking goal at x = 1 regardless of which side actually shot.
    private String X;
    private String Y;

    // Understat's own precomputed xG - kept only for reference/comparison,
    // never used as an input to our own model.
    private String xG;

    private String player;
    private String h_a;
    private String situation;
    private String shotType;
    private String match_id;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMinute() { return minute; }
    public void setMinute(String minute) { this.minute = minute; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getX() { return X; }
    public void setX(String x) { X = x; }

    public String getY() { return Y; }
    public void setY(String y) { Y = y; }

    public String getUnderstatXg() { return xG; }
    public void setXg(String xG) { this.xG = xG; }

    public String getPlayer() { return player; }
    public void setPlayer(String player) { this.player = player; }

    public String getHomeOrAway() { return h_a; }
    public void setH_a(String h_a) { this.h_a = h_a; }

    public String getSituation() { return situation; }
    public void setSituation(String situation) { this.situation = situation; }

    public String getShotType() { return shotType; }
    public void setShotType(String shotType) { this.shotType = shotType; }

    public String getMatchId() { return match_id; }
    public void setMatch_id(String match_id) { this.match_id = match_id; }

    public double parsedX() { return Double.parseDouble(X); }
    public double parsedY() { return Double.parseDouble(Y); }
}
