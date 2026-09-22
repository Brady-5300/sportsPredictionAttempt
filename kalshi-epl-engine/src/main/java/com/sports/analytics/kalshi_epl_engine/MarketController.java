package com.sports.analytics.kalshi_epl_engine;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/kalshi")
@CrossOrigin(origins = "*")
public class MarketController {

    private final KalshiMarketService kalshiMarketService;

    public MarketController(KalshiMarketService kalshiMarketService) {
        this.kalshiMarketService = kalshiMarketService;
    }

    @GetMapping("/scan")
    public List<MarketEvaluation> scanMarkets(
            @RequestParam(defaultValue = "1.50") double homeXG,
            @RequestParam(defaultValue = "1.00") double awayXG) {
        return kalshiMarketService.evaluateLiveMarkets(homeXG, awayXG);
    }

    @GetMapping("/raw")
    public String getRawMarkets() {
        return kalshiMarketService.fetchRawMarkets();
    }
}