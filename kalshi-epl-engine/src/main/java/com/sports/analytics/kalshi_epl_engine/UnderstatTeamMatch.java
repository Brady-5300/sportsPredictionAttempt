package com.sports.analytics.kalshi_epl_engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * One row from a team's Understat "datesData" schedule: a past or upcoming fixture
 * summary. Used only to find match ids for a team's recent completed games -
 * the actual shot-level data for xG comes from the per-match shots endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UnderstatTeamMatch {

    private String id;
    private boolean isResult;
    private Map<String, String> h;
    private Map<String, String> a;
    private String datetime;
    private String side;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isResult() { return isResult; }
    public void setIsResult(boolean isResult) { this.isResult = isResult; }

    /** "h" if the team whose schedule this came from played at home, "a" if away. */
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public Map<String, String> getH() { return h; }
    public void setH(Map<String, String> h) { this.h = h; }

    public Map<String, String> getA() { return a; }
    public void setA(Map<String, String> a) { this.a = a; }

    public String getDatetime() { return datetime; }
    public void setDatetime(String datetime) { this.datetime = datetime; }

    public String getHomeTeamTitle() { return h == null ? null : h.get("title"); }
    public String getAwayTeamTitle() { return a == null ? null : a.get("title"); }
}
