package com.sports.analytics.kalshi_epl_engine;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kalshi")
@CrossOrigin(origins = "*")
public class MarketController {

    private final KalshiMarketService kalshiMarketService;

    public MarketController(KalshiMarketService kalshiMarketService) {
        this.kalshiMarketService = kalshiMarketService;
    }

    @GetMapping("/scan")
    public MarketScanResult scanMarkets() {
        return kalshiMarketService.evaluateLiveMarkets();
    }

    @GetMapping("/raw")
    public String getRawMarkets() {
        return kalshiMarketService.fetchRawMarkets();
    }
}
