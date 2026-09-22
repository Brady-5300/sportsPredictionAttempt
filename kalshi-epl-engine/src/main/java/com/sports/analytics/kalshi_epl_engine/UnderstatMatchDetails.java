package com.sports.analytics.kalshi_epl_engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Combined shots + rosters for one match, fetched in a single request. */
public record UnderstatMatchDetails(
    Map<String, List<UnderstatShot>> shots,
    Map<String, List<UnderstatPlayerMatchStat>> rosters
) {
    public static UnderstatMatchDetails empty() {
        return new UnderstatMatchDetails(
            Map.of("h", new ArrayList<>(), "a", new ArrayList<>()),
            Map.of("h", new ArrayList<>(), "a", new ArrayList<>())
        );
    }
}
