package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.util.AbstractMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TickerParserService {

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
        return name.replaceAll("(?i)\\b(to win|win|draw|tie)\\b", "").trim();
    }
}