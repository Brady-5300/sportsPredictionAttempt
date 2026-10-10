package com.sports.analytics.kalshi_epl_engine;

import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a weekend wager across the upcoming weekend's "YES (Undervalued)"
 * markets, in proportion to each one's quarter-Kelly stake - which already
 * combines how big the edge is with the price - so bigger, better-priced
 * edges get more. Kalshi only sells whole contracts, so each pick also shows
 * how many contracts its share buys and what that actually costs.
 */
@Service
public class WeekendAllocationService {

    private final PoissonModel poissonModel;

    public WeekendAllocationService(PoissonModel poissonModel) {
        this.poissonModel = poissonModel;
    }

    /** "Upcoming weekend" = kickoffs from now through the end of the coming Monday (today, if it's Monday). */
    static ZonedDateTime weekendEnd(ZonedDateTime now) {
        return now.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).with(LocalTime.MAX);
    }

    public WeekendAllocation allocate(double budgetDollars, List<MarketEvaluation> evaluations, ZonedDateTime now) {
        ZonedDateTime windowEnd = weekendEnd(now);

        List<MarketEvaluation> picks = evaluations.stream()
            .filter(e -> !e.isLive())
            .filter(e -> e.getRecommendation() != null && e.getRecommendation().startsWith("YES"))
            .filter(e -> e.getRecommendedWagerPercent() > 0)
            .filter(e -> e.getKickoff() != null)
            .filter(e -> {
                ZonedDateTime kickoff = Instant.parse(e.getKickoff()).atZone(now.getZone());
                return kickoff.isAfter(now) && !kickoff.isAfter(windowEnd);
            })
            .toList();

        double totalWeight = picks.stream().mapToDouble(MarketEvaluation::getRecommendedWagerPercent).sum();
        List<WeekendAllocation.Pick> result = new ArrayList<>();
        double allocated = 0.0;
        double cost = 0.0;
        for (MarketEvaluation e : picks) {
            double share = e.getRecommendedWagerPercent() / totalWeight;
            double dollars = budgetDollars * share;
            int fee = poissonModel.calculateKalshiFeeCents(e.getKalshiPriceCents());
            int perContractCents = e.getKalshiPriceCents() + fee;
            int contracts = (int) Math.floor(dollars * 100 / perContractCents);
            double pickCost = contracts * perContractCents / 100.0;

            allocated += dollars;
            cost += pickCost;
            result.add(new WeekendAllocation.Pick(e.getTicker(), e.getTitle(), e.getKickoff(),
                e.getKalshiPriceCents(), fee, e.getModelProbability(), e.getEdge(),
                round2(share * 100), round2(dollars), contracts, round2(pickCost), contracts));
        }

        return new WeekendAllocation(budgetDollars, windowEnd.toOffsetDateTime().toString(), result,
            round2(allocated), round2(cost), round2(budgetDollars - cost));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
