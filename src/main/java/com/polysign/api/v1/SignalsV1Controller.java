package com.polysign.api.v1;

import com.polysign.backtest.SignalPerformanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

/**
 * GET /api/v1/signals/performance
 *
 * <p>Returns backtested precision and magnitude metrics per detector. Not paginated —
 * the dataset is small (5 known detector types × 3 horizons).
 *
 * <p>Delegates to {@link SignalPerformanceService} — the same source used by the
 * internal dashboard, so public and private data are always consistent.
 */
@Tag(name = "Signals", description = "Backtested signal precision and magnitude")
@RestController
@RequestMapping("/api/v1/signals")
public class SignalsV1Controller {

    private final SignalPerformanceService signalPerformanceService;

    public SignalsV1Controller(SignalPerformanceService signalPerformanceService) {
        this.signalPerformanceService = signalPerformanceService;
    }

    @Operation(summary = "Signal performance metrics",
               description = "Backtested precision per detector type. Not paginated.")
    @GetMapping("/performance")
    public SignalPerformanceService.PerformanceResponse performance(
            @RequestParam(required = false) String horizon,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String since) {

        java.time.Instant sinceInstant = null;
        if (since != null && !since.isBlank()) {
            try {
                sinceInstant = java.time.Instant.parse(since);
            } catch (java.time.format.DateTimeParseException ignored) {
                // default (7-day lookback) used when since is absent or unparseable
            }
        }
        return signalPerformanceService.getPerformance(type, horizon, sinceInstant);
    }
}
