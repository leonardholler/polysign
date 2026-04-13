package com.polysign.api;

import com.polysign.backtest.SignalPerformanceService;
import com.polysign.backtest.SignalPerformanceService.ResolutionCoverageResponse;
import com.polysign.common.AppClock;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Diagnostic endpoints at {@code /actuator/diagnostics/**}.
 *
 * <p>Not guarded by API-key auth (filters are registered for {@code /api/v1/*} only).
 * Intended for local/ops use — do not expose externally without additional controls.
 */
@RestController
@RequestMapping("/actuator/diagnostics")
public class DiagnosticsEndpoint {

    private final SignalPerformanceService service;
    private final AppClock clock;

    public DiagnosticsEndpoint(SignalPerformanceService service, AppClock clock) {
        this.service = service;
        this.clock   = clock;
    }

    /**
     * GET /actuator/diagnostics/resolution-coverage
     *
     * <p>Per-category for the last 7 days:
     * <ul>
     *   <li>{@code totalMarkets}      — markets in the DB with that category</li>
     *   <li>{@code resolvedMarkets}   — markets where {@code resolvedOutcomePrice} is set</li>
     *   <li>{@code marketsWithSignal} — distinct market IDs that fired at least one signal</li>
     *   <li>{@code signalsTotal}      — total outcome rows in the period</li>
     *   <li>{@code signalsResolved}   — outcome rows where {@code wasCorrect} is non-null</li>
     * </ul>
     */
    @GetMapping("/resolution-coverage")
    public ResponseEntity<ResolutionCoverageResponse> resolutionCoverage() {
        return ResponseEntity.ok(
                service.getResolutionCoverage(clock.now().minus(Duration.ofDays(7))));
    }
}
