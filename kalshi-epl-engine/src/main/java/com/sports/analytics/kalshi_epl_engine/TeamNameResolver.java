package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

/**
 * Central place for translating a team name (however it shows up in a Kalshi
 * market title/ticker, or an Understat fixture) into the various canonical
 * forms other services need: a normalized lookup key, a Kalshi ticker code,
 * and an Understat URL slug.
 *
 * Pulled out of XgService so UnderstatXgProvider can depend on it too without
 * creating a cycle (XgService -> UnderstatXgProvider -> team lookups -> XgService).
 */
@Service
public class TeamNameResolver {

    public String normalizeTeamName(String name) {
        if (name == null) return "";
        String cleaned = name.toLowerCase().trim();

        switch (cleaned) {
            case "mun", "man utd", "manchester united": return "manchester united";
            case "mci", "man city", "manchester city": return "manchester city";
            case "ars", "arsenal": return "arsenal";
            case "che", "cfc", "chelsea": return "chelsea";
            case "liv", "lfc", "liverpool": return "liverpool";
            case "tot", "tottenham", "tottenham hotspur": return "tottenham";
            case "new", "newcastle", "newcastle united": return "newcastle";
            case "avl", "aston villa": return "aston villa";
            case "bri", "brighton", "brighton and hove albion": return "brighton";
            case "cry", "crystal palace": return "crystal palace";
            case "ful", "fulham": return "fulham";
            case "bre", "brentford": return "brentford";
            case "bou", "bournemouth": return "bournemouth";
            case "eve", "everton": return "everton";
            case "nfo", "nottingham", "nottingham forest": return "nottingham forest";
            case "sun", "sunderland": return "sunderland";
            case "lee", "leeds", "leeds united": return "leeds united";
            case "hul", "hull", "hull city": return "hull city";
            case "cov", "coventry", "coventry city": return "coventry";
            case "ips", "ipswich", "ipswich town": return "ipswich town";
            case "whu", "west ham", "west ham united": return "west ham";
            case "wol", "wolves", "wolverhampton", "wolverhampton wanderers": return "wolverhampton";
            case "bur", "burnley": return "burnley";
            default: return cleaned;
        }
    }

    /**
     * Returns the canonical Kalshi ticker code (e.g. "MUN", "FUL") for a team name.
     * Used to match ticker suffixes exactly instead of via substring heuristics,
     * which avoids false matches when one team's name/code is a substring of another's.
     * Codes are Kalshi's own (e.g. Chelsea is "CFC" and Liverpool "LFC" in KXEPLGAME tickers).
     */
    public String getTeamCode(String teamName) {
        String key = normalizeTeamName(teamName);
        switch (key) {
            case "manchester united": return "MUN";
            case "manchester city": return "MCI";
            case "arsenal": return "ARS";
            case "chelsea": return "CFC";
            case "liverpool": return "LFC";
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
            case "west ham": return "WHU";
            case "wolverhampton": return "WOL";
            case "burnley": return "BUR";
            default: return "";
        }
    }

    /**
     * Returns the URL slug Understat uses for a team (e.g. "Manchester_United"),
     * or null if we don't have a mapping for it - callers then skip the team
     * rather than guess.
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
            case "hull city": return "Hull";
            case "coventry": return "Coventry";
            case "ipswich town": return "Ipswich";
            case "west ham": return "West_Ham";
            case "wolverhampton": return "Wolverhampton_Wanderers";
            case "burnley": return "Burnley";
            default: return null;
        }
    }
}
