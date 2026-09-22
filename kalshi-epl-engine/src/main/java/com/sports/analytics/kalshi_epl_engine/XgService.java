package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

@Service
public class XgService {

    public double calculateHomeXG(String homeTeam, String awayTeam) {
        String homeKey = normalizeTeamName(homeTeam);
        String awayKey = normalizeTeamName(awayTeam);

        double homeAttack = getAttackRating(homeKey);
        double awayDefense = getDefenseRating(awayKey);

        double xg = (homeAttack * 0.6) + ((2.0 - awayDefense) * 0.4);
        return Math.round(xg * 100.0) / 100.0;
    }

    public double calculateAwayXG(String homeTeam, String awayTeam) {
        String homeKey = normalizeTeamName(homeTeam);
        String awayKey = normalizeTeamName(awayTeam);

        double awayAttack = getAttackRating(awayKey);
        double homeDefense = getDefenseRating(homeKey);

        double xg = (awayAttack * 0.6) + ((2.0 - homeDefense) * 0.4);
        return Math.round(xg * 100.0) / 100.0;
    }

    // Helper to safely parse teams from Kalshi market titles or tickers
    public String[] parseTeamsFromKalshi(String title, String ticker) {
        // Default fallback
        String home = "";
        String away = "";

        try {
            if (title != null && title.contains(" vs ")) {
                // Example title: "Fulham vs Manchester United: Fulham wins"
                String[] mainSplit = title.split(":");
                String matchupPart = mainSplit[0].trim(); // "Fulham vs Manchester United"
                String[] teams = matchupPart.split(" vs ");
                if (teams.length >= 2) {
                    home = teams[0].trim();
                    away = teams[1].trim();
                }
            } else if (ticker != null && ticker.contains("-")) {
                // Fallback to ticker parsing if title isn't available (e.g., "KXEPLGAME-26SEP20FULMUN-FUL")
                String[] parts = ticker.split("-");
                if (parts.length >= 2) {
                    String matchCode = parts[1]; // e.g., "26SEP20FULMUN"
                    // Extract 3-letter codes if embedded at the end of the date code
                    if (matchCode.length() >= 6) {
                        home = matchCode.substring(matchCode.length() - 6, matchCode.length() - 3);
                        away = matchCode.substring(matchCode.length() - 3);
                    }
                }
            }
        } catch (Exception e) {
            // Fallback gracefully if parsing fails
        }

        return new String[] { home, away };
    }

    private double getAttackRating(String teamKey) {
        switch (teamKey) {
            case "arsenal": return 1.85;
            case "manchester city": return 2.10;
            case "liverpool": return 1.90;
            case "chelsea": return 1.60;
            case "tottenham": return 1.65;
            case "aston villa": return 1.55;
            case "newcastle": return 1.50;
            case "manchester united": return 1.45;
            case "brighton": return 1.40;
            case "crystal palace": return 1.25;
            case "fulham": return 1.25;
            case "brentford": return 1.30;
            case "bournemouth": return 1.30;
            case "everton": return 1.15;
            case "nottingham forest": return 1.20;
            case "sunderland": return 1.10;
            case "leeds united": return 1.20;
            case "hull city": return 1.05;
            case "coventry": return 1.05;
            case "ipswich town": return 1.00;
            default: return 1.35;
        }
    }

    private double getDefenseRating(String teamKey) {
        switch (teamKey) {
            case "arsenal": return 0.75;
            case "manchester city": return 0.80;
            case "liverpool": return 0.85;
            case "chelsea": return 1.00;
            case "tottenham": return 1.10;
            case "aston villa": return 1.15;
            case "newcastle": return 1.10;
            case "manchester united": return 1.20;
            case "brighton": return 1.20;
            case "crystal palace": return 1.25;
            case "fulham": return 1.30;
            case "brentford": return 1.35;
            case "bournemouth": return 1.40;
            case "everton": return 1.25;
            case "nottingham forest": return 1.30;
            case "sunderland": return 1.40;
            case "leeds united": return 1.35;
            case "hull city": return 1.45;
            case "coventry": return 1.45;
            case "ipswich town": return 1.50;
            default: return 1.25;
        }
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

    private String normalizeTeamName(String name) {
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
}