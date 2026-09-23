package com.sports.analytics.kalshi_epl_engine;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/validate")
@CrossOrigin(origins = "*")
public class ModelValidationController {

    private final ModelValidationService modelValidationService;

    public ModelValidationController(ModelValidationService modelValidationService) {
        this.modelValidationService = modelValidationService;
    }

    /**
     * Checks the model's calibration against up to {@code maxEvents} historical
     * settled EPL matches. Warning: fetches each event's markets separately, so
     * this can take a while for a large maxEvents (roughly 1 request per event,
     * rate-limit-paced).
     */
    @GetMapping("/run")
    public ModelValidationReport runValidation(@RequestParam(defaultValue = "150") int maxEvents) {
        return modelValidationService.run(maxEvents);
    }
}
