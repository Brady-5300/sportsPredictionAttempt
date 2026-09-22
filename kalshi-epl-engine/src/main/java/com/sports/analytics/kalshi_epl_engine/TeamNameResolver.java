package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

/**
 * Central place for translating a team name (however it shows up in a Kalshi
 * market title/ticker) into the various canonical forms other services need:
 * a normalized lookup key, a Kalshi ticker code, and an Understat URL slug.
 *
 * Pulled out of XgService so UnderstatXgProvider can depend on it too without
 * creating a cycle (XgService -> UnderstatXgProvider -> team lookups -> XgService).
 */
@Service
public class TeamNameResolver {

    public String normalizeTeamName(String name) {
        if (name == null) return "";
        String cleaned = name.toLowerCase().trim();

        if (cleaned.equals("mun") || cleaned.equals("man utd") || cleaned.equals("manchester united")) return "manchester united";
        if (cleaned.equals("mci") || cleaned.equals("man city") || cleaned.equals("manchester city")) return "manchester city";
        if (cleaned.equals("ars") || cleaned.equals("arsenal")) return "arsenal";
        if (cleaned.equals("che") || cleaned.equals("cfc") || cleaned.equals("chelsea")) return "chelsea";
        if (cleaned.equals("liv") || cleaned.equals("lfc") || cleaned.equals("liverpool")) return "liverpool";
        if (cleaned.equals("tot") || cleaned.equals("tottenham")) return "tottenham";
        if (cleaned.equals("new") || cleaned.equals("newcastle")) return "newcastle";
        if (cleaned.equals("avl") || cleaned.equals("aston villa")) return "aston villa";
        if (cleaned.equals("bri") || cleaned.equals("brighton")) return "brighton";
        if (cleaned.equals("cry") || cleaned.equals("crystal palace")) return "crystal palace";
        if (cleaned.equals("ful") || cleaned.equals("fulham")) return "fulham";
        if (cleaned.equals("bre") || cleaned.equals("brentford")) return "brentford";
        if (cleaned.equals("bou") || cleaned.equals("bournemouth")) return "bournemouth";
        if (cleaned.equals("eve") || cleaned.equals("everton")) return "everton";
        if (cleaned.equals("nfo") || cleaned.equals("nottingham forest")) return "nottingham forest";
        if (cleaned.equals("sun") || cleaned.equals("sunderland")) return "sunderland";
        if (cleaned.equals("lee") || cleaned.equals("leeds united")) return "leeds united";
        if (cleaned.equals("hul") || cleaned.equals("hull city")) return "hull city";
        if (cleaned.equals("cov") || cleaned.equals("coventry")) return "coventry";
        if (cleaned.equals("ips") || cleaned.equals("ipswich town")) return "ipswich town";

        return cleaned;
    }

    /**
     * Returns the canonical Kalshi ticker code (e.g. "MUN", "FUL") for a team name.
     * Used to match ticker suffixes exactly instead of via substring heuristics,
     * which avoids false matches when one team's name/code is a substring of another's.
     */
    public String getTeamCode(String teamName) {
        String key = normalizeTeamName(teamName);
        switch (key) {
            case "manchester united": return "MUN";
            case "manchester city": return "MCI";
            case "arsenal": return "ARS";
            case "chelsea": return "CHE";
            case "liverpool": return "LIV";
            case "tottenham": return "TOT";
            case "newcastle": return "NEW";
            case "aston villa": return "AVL";
            case "brighton": return "BRI";
            case "crystal palace": return "CRY";
            case "fulham": return "FUL";
            case "brentford": return "BRE";
            case "bournemouth": return "BOU";
            case "everton": return "EVE";
            case "nottingham forest": return "NFO";
            case "sunderland": return "SUN";
            case "leeds united": return "LEE";
            case "hull city": return "HUL";
            case "coventry": return "COV";
            case "ipswich town": return "IPS";
            default: return "";
        }
    }

    /**
     * Returns the URL slug Understat uses for a team (e.g. "Manchester_United"),
     * or null if we don't have a mapping for it. A null here means live xG data
     * can't be fetched for that team and callers should fall back to static data.
     */
    public String getUnderstatSlug(String teamName) {
        String key = normalizeTeamName(teamName);
        switch (key) {
            case "arsenal": return "Arsenal";
            case "manchester city": return "Manchester_City";
            case "liverpool": return "Liverpool";
            case "chelsea": return "Chelsea";
            case "tottenham": return "Tottenham";
            case "aston villa": return "Aston_Villa";
            case "newcastle": return "Newcastle_United";
            case "manchester united": return "Manchester_United";
            case "brighton": return "Brighton";
            case "crystal palace": return "Crystal_Palace";
            case "fulham": return "Fulham";
            case "brentford": return "Brentford";
            case "bournemouth": return "Bournemouth";
            case "everton": return "Everton";
            case "nottingham forest": return "Nottingham_Forest";
            case "sunderland": return "Sunderland";
            case "leeds united": return "Leeds";
            default: return null; // no reliable Understat slug for this team
        }
    }
}
