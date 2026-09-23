package com.sports.analytics.kalshi_epl_engine;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/prediction-log")
@CrossOrigin(origins = "*")
public class PredictionLogController {

    private final PredictionLogService predictionLogService;

    public PredictionLogController(PredictionLogService predictionLogService) {
        this.predictionLogService = predictionLogService;
    }

    /** All predictions logged live so far, both resolved and still pending. */
    @GetMapping("/entries")
    public List<PredictionLogEntry> entries() {
        return predictionLogService.allEntries();
    }

    /**
     * Calibration stats (Brier score, log-loss, calibration buckets) over only
     * the live-logged predictions that have resolved so far - directly
     * comparable to /api/validate/run's numbers, but using the FULL pipeline
     * including FotMob lineup adjustments, which the historical validator
     * can't include.
     */
    @GetMapping("/calibration")
    public ModelValidationReport calibration() {
        return predictionLogService.calibrationReport();
    }

    /** Our Brier score vs. Kalshi's pre-kickoff price on the same resolved markets. */
    @GetMapping("/vs-market")
    public MarketComparison vsMarket() {
        return predictionLogService.marketComparison();
    }

    /** Full model vs. the same model without lineup adjustments, on the same resolved markets. */
    @GetMapping("/lineup-effect")
    public LineupComparison lineupEffect() {
        return predictionLogService.lineupComparison();
    }
}
