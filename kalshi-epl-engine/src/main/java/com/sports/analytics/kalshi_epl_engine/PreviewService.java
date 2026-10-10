package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a preview for each upcoming match: who's favoured (model and Kalshi),
 * expected goals, likely scorelines, and a side-by-side of both teams'
 * ratings, league ranks, recent form and most dangerous attackers.
 */
@Service
public class PreviewService {

    private static final int MAX_GOALS = 10;
    private static final int FORM_MATCHES = 5;
    private static final int THREATS = 3;
    // Only count players with at least this share of the most-used player's minutes, so a
    // sub's lucky 20 minutes doesn't make him look like the team's best finisher.
    private static final double THREAT_MIN_MINUTES_SHARE = 0.5;
    private static final int MAX_USABLE_SPREAD_CENTS = 10;

    private final XgService xgService;
    private final UnderstatXgProvider understatXgProvider;
    private final PoissonModel poissonModel;
    private final TeamNameResolver teamNameResolver;

    public PreviewService(XgService xgService, UnderstatXgProvider understatXgProvider,
                          PoissonModel poissonModel, TeamNameResolver teamNameResolver) {
        this.xgService = xgService;
        this.understatXgProvider = understatXgProvider;
        this.poissonModel = poissonModel;
        this.teamNameResolver = teamNameResolver;
    }

    /** One preview per match in the scan, in kickoff order. */
    public List<MatchPreview> build(List<MarketEvaluation> evaluations) {
        Map<String, List<MarketEvaluation>> byMatch = new LinkedHashMap<>();
        for (MarketEvaluation e : evaluations) {
            if (e.isLive()) continue; // previews are the pre-match view
            byMatch.computeIfAbsent(matchKey(e.getTicker()), k -> new ArrayList<>()).add(e);
        }

        Map<String, UnderstatXgProvider.SeasonStats> league = understatXgProvider.getCurrentLeagueSeasonStats();
        List<MatchPreview> previews = new ArrayList<>();
        for (List<MarketEvaluation> markets : byMatch.values()) {
            String[] teams = teamsOf(markets.get(0).getTitle());
            if (teams == null) continue;
            buildOne(teams[0], teams[1], markets, league).ifPresent(previews::add);
        }
        previews.sort(Comparator.comparing(MatchPreview::kickoff, Comparator.nullsLast(Comparator.naturalOrder())));
        return previews;
    }

    private Optional<MatchPreview> buildOne(String home, String away, List<MarketEvaluation> markets,
                                            Map<String, UnderstatXgProvider.SeasonStats> league) {
        Optional<Double> homeXg = xgService.calculateHomeXG(home, away);
        Optional<Double> awayXg = xgService.calculateAwayXG(home, away);
        if (homeXg.isEmpty() || awayXg.isEmpty()) return Optional.empty();

        double[][] grid = poissonModel.scorelineProbabilities(homeXg.get(), awayXg.get(), MAX_GOALS);
        double homeWin = 0, draw = 0, awayWin = 0, over = 0;
        for (int h = 0; h <= MAX_GOALS; h++) {
            for (int a = 0; a <= MAX_GOALS; a++) {
                double p = grid[h][a];
                if (h > a) homeWin += p; else if (h < a) awayWin += p; else draw += p;
                if (h + a > 2) over += p;
            }
        }
        double noHomeGoal = 0, noAwayGoal = 0;
        for (int i = 0; i <= MAX_GOALS; i++) {
            noHomeGoal += grid[0][i];
            noAwayGoal += grid[i][0];
        }
        double bothScore = 1 - noHomeGoal - noAwayGoal + grid[0][0];

        Double[] kalshi = kalshiProbabilities(markets);
        String kickoff = markets.get(0).getKickoff();
        return Optional.of(new MatchPreview(home, away, kickoff,
            round4(homeWin), round4(draw), round4(awayWin), kalshi[0], kalshi[1], kalshi[2],
            homeXg.get(), awayXg.get(), round4(bothScore), round4(over),
            panel(home, league), panel(away, league)));
    }

    /** Kalshi's home/draw/away probabilities (bid/ask midpoints), scaled to add up to 100%. */
    private Double[] kalshiProbabilities(List<MarketEvaluation> markets) {
        Double home = null, draw = null, away = null;
        for (MarketEvaluation m : markets) {
            Integer bid = m.getYesBidCents(), ask = m.getYesAskCents();
            if (bid == null || ask == null || ask <= bid || ask - bid > MAX_USABLE_SPREAD_CENTS) continue;
            double mid = (bid + ask) / 200.0;
            switch (m.getMarketType() == null ? "" : m.getMarketType()) {
                case "HOME" -> home = mid;
                case "TIE" -> draw = mid;
                case "AWAY" -> away = mid;
                default -> { }
            }
        }
        if (home == null || draw == null || away == null) return new Double[]{null, null, null};
        double total = home + draw + away;
        return new Double[]{round4(home / total), round4(draw / total), round4(away / total)};
    }

    /** This season only: record, goals, xG per match with league ranks, form and top threats. */
    private MatchPreview.TeamPanel panel(String team, Map<String, UnderstatXgProvider.SeasonStats> league) {
        UnderstatXgProvider.SeasonStats season = understatXgProvider.getSeasonStats(team).orElse(null);
        String slug = teamNameResolver.getUnderstatSlug(team);
        Integer xgForRank = null, xgAgainstRank = null;
        if (season != null && slug != null && league.containsKey(slug)) {
            xgForRank = 1 + (int) league.values().stream().filter(s -> s.xgForPerMatch() > season.xgForPerMatch()).count();
            xgAgainstRank = 1 + (int) league.values().stream().filter(s -> s.xgAgainstPerMatch() < season.xgAgainstPerMatch()).count();
        }
        return new MatchPreview.TeamPanel(team, season, xgForRank, xgAgainstRank, league.size(),
            understatXgProvider.getRecentForm(team, FORM_MATCHES), threats(team));
    }

    /**
     * Top contributors this season by goals + assists (ties broken by xG + xA per 90),
     * from Understat's season table - display only, the model doesn't use these.
     */
    private List<MatchPreview.Threat> threats(String team) {
        List<UnderstatPlayerSeason> players = understatXgProvider.getSeasonPlayers(team);
        int maxMinutes = players.stream().mapToInt(UnderstatPlayerSeason::minutes).max().orElse(0);
        return players.stream()
            .filter(p -> p.minutes() > 0 && p.minutes() >= THREAT_MIN_MINUTES_SHARE * maxMinutes)
            .sorted(Comparator.comparingInt((UnderstatPlayerSeason p) -> p.goals() + p.assists())
                .thenComparingDouble(p -> (p.xg() + p.xa()) / p.minutes())
                .reversed())
            .limit(THREATS)
            .map(p -> new MatchPreview.Threat(p.player(), p.goals(), p.assists(), p.minutes(),
                round2(p.xg() / p.minutes() * 90), round2(p.xa() / p.minutes() * 90)))
            .toList();
    }

    /** "Arsenal vs Leeds United: Leeds United wins" -> ["Arsenal", "Leeds United"]; null if it can't tell. */
    static String[] teamsOf(String title) {
        if (title == null) return null;
        int colon = title.indexOf(':');
        String match = (colon >= 0 ? title.substring(0, colon) : title).replaceAll("(?i)\\s+winner\\??$", "").trim();
        String[] teams = match.split("\\s+vs\\.?\\s+");
        return teams.length == 2 ? new String[]{teams[0].trim(), teams[1].trim()} : null;
    }

    private static String matchKey(String ticker) {
        int lastDash = ticker.lastIndexOf('-');
        return lastDash > 0 ? ticker.substring(0, lastDash) : ticker;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
