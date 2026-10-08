package com.sports.analytics.kalshi_epl_engine;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/kalshi")
@CrossOrigin(origins = "*")
public class MarketController {

    private final KalshiMarketService kalshiMarketService;
    private final WeekendAllocationService weekendAllocationService;

    public MarketController(KalshiMarketService kalshiMarketService, WeekendAllocationService weekendAllocationService) {
        this.kalshiMarketService = kalshiMarketService;
        this.weekendAllocationService = weekendAllocationService;
    }

    @GetMapping("/scan")
    public MarketScanResult scanMarkets() {
        return kalshiMarketService.evaluateLiveMarkets();
    }

    /** Splits {@code budget} dollars across this weekend's undervalued markets. */
    @GetMapping("/allocate")
    public ResponseEntity<?> allocate(@RequestParam double budget) {
        if (!(budget > 0) || budget > 1_000_000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Budget must be a positive dollar amount."));
        }
        MarketScanResult scan = kalshiMarketService.evaluateLiveMarkets();
        if (!MarketScanResult.STATUS_OK.equals(scan.status())) {
            return ResponseEntity.status(503).body(Map.of("error", scan.message() == null ? "Data source offline." : scan.message()));
        }
        List<MarketEvaluation> evaluations = scan.evaluations();
        return ResponseEntity.ok(weekendAllocationService.allocate(budget, evaluations, ZonedDateTime.now()));
    }

    @GetMapping("/raw")
    public String getRawMarkets() {
        return kalshiMarketService.fetchRawMarkets();
    }
}
