package com.sports.analytics.kalshi_epl_engine;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/predictions")
public class PredictionController {

    private final PoissonModel poissonModel;

    public PredictionController(PoissonModel poissonModel) {
        this.poissonModel = poissonModel;
    }

    // Endpoint: http://localhost:8080/api/predictions/evaluate?homeXG=2.10&awayXG=0.85&kalshiPriceCents=52&marketType=HOME
    @GetMapping("/evaluate")
    public Map<String, Object> evaluateEdge(
            @RequestParam double homeXG,
            @RequestParam double awayXG,
            @RequestParam int kalshiPriceCents,
            @RequestParam(defaultValue = "HOME") String marketType) {

        double modelProb = poissonModel.calculateMarketProbability(homeXG, awayXG, marketType);
        double marketProb = kalshiPriceCents / 100.0;
        double edge = modelProb - marketProb;

        Map<String, Object> response = new HashMap<>();
        response.put("market_type", marketType.toUpperCase());
        response.put("model_probability", Math.round(modelProb * 10000.0) / 100.0 + "%");
        response.put("kalshi_market_probability", marketProb * 100.0 + "%");
        response.put("edge", Math.round(edge * 10000.0) / 100.0 + "%");
        response.put("recommendation", edge > 0.05 ? "BUY YES (Undervalued)" : "PASS / NO EDGE");

        return response;
    }
}