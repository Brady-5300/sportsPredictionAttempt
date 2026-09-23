package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TickerParserService {

    // Kalshi ticker match codes embed the date as yyMONdd, e.g. "26SEP20FULMUN" -> 2026-09-20.
    private static final Pattern TICKER_DATE_PATTERN = Pattern.compile("-(\\d{2})([A-Z]{3})(\\d{2})[A-Z]{6}-");

    private static final Pattern[] PATTERNS = new Pattern[]{
        // Matches: "Will Chelsea beat Brentford?" or "Will Chelsea win against Brentford?"
        Pattern.compile("Will\\s+(.+?)\\s+(?:beat|win against|defeat)\\s+(.+?)\\?", Pattern.CASE_INSENSITIVE),
        // Matches: "Brentford vs Chelsea: Chelsea" or "Brentford vs. Chelsea"
        Pattern.compile("(.+?)\\s+vs\\.?\\s+(.+?)(?::|-|$)", Pattern.CASE_INSENSITIVE),
        // Matches: "Brentford v Chelsea"
        Pattern.compile("(.+?)\\s+v\\s+(.+?)(?::|-|$)", Pattern.CASE_INSENSITIVE)
    };

    public Map.Entry<String, String> extractTeamsFromTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }

        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(title);
            if (matcher.find()) {
                String home = cleanTeamName(matcher.group(1));
                String away = cleanTeamName(matcher.group(2));

                if (!home.isEmpty() && !away.isEmpty()) {
                    return new AbstractMap.SimpleEntry<>(home, away);
                }
            }
        }
        return null;
    }

    private String cleanTeamName(String name) {
        // Kalshi titles some markets "Home vs Away Winner?" - strip that suffix too.
        return name.replaceAll("(?i)\\b(to win|winner|win|draw|tie)\\b", "").replace("?", "").trim();
    }

    /**
     * Extracts the match date embedded in a Kalshi ticker, e.g.
     * "KXEPLGAME-26SEP20FULMUN-FUL" -> 2026-09-20. Returns empty if the
     * ticker doesn't match the expected shape (defensive - ticker format
     * could change, and this is only used to decide freshness, not
     * correctness-critical).
     */
    public Optional<LocalDate> parseMatchDateFromTicker(String ticker) {
        if (ticker == null) return Optional.empty();

        Matcher matcher = TICKER_DATE_PATTERN.matcher(ticker);
        if (!matcher.find()) return Optional.empty();

        try {
            int year = 2000 + Integer.parseInt(matcher.group(1));
            Month month = Month.valueOf(fullMonthName(matcher.group(2)));
            int day = Integer.parseInt(matcher.group(3));
            return Optional.of(LocalDate.of(year, month, day));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String fullMonthName(String abbreviation) {
        switch (abbreviation.toUpperCase()) {
            case "JAN": return "JANUARY";
            case "FEB": return "FEBRUARY";
            case "MAR": return "MARCH";
            case "APR": return "APRIL";
            case "MAY": return "MAY";
            case "JUN": return "JUNE";
            case "JUL": return "JULY";
            case "AUG": return "AUGUST";
            case "SEP": return "SEPTEMBER";
            case "OCT": return "OCTOBER";
            case "NOV": return "NOVEMBER";
            case "DEC": return "DECEMBER";
            default: throw new IllegalArgumentException("Unknown month abbreviation: " + abbreviation);
        }
    }
}