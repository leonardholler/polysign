package com.polysign.api;

import com.polysign.backtest.SignalPerformanceService;
import com.polysign.backtest.SignalPerformanceService.ResolutionCoverageResponse;
import com.polysign.common.AppClock;
import com.polysign.detector.PriceMovementDetector;
import com.polysign.detector.StatisticalAnomalyDetector;
import com.polysign.detector.WalletActivityDetector;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Diagnostic endpoints at {@code /actuator/diagnostics/**}.
 *
 * <p>Not guarded by API-key auth (filters are registered for {@code /api/v1/*} only).
 * Intended for local/ops use — do not expose externally without additional controls.
 */
@RestController
@RequestMapping("/actuator/diagnostics")
public class DiagnosticsEndpoint {

    private final SignalPerformanceService   service;
    private final AppClock                   clock;
    private final PriceMovementDetector      priceDetector;
    private final StatisticalAnomalyDetector statDetector;
    private final WalletActivityDetector     whaleDetector;

    public DiagnosticsEndpoint(SignalPerformanceService service,
                                AppClock clock,
                                PriceMovementDetector priceDetector,
                                StatisticalAnomalyDetector statDetector,
                                WalletActivityDetector whaleDetector) {
        this.service       = service;
        this.clock         = clock;
        this.priceDetector = priceDetector;
        this.statDetector  = statDetector;
        this.whaleDetector = whaleDetector;
    }

    /** Top-level response for {@code GET /actuator/diagnostics/detector-thresholds}. */
    record DetectorThresholdsResponse(
            PriceMovementDetector.PriceDetectorDiagnostics priceMovement,
            StatisticalAnomalyDetector.StatDetectorDiagnostics statisticalAnomaly,
            WalletActivityDetector.WhaleDetectorDiagnostics whale,
            Map<String, Long> combinedFilterCountsLastHour) {}

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

    /**
     * GET /actuator/diagnostics/detector-thresholds
     *
     * <p>Returns the current effective configuration and last-run stats for all three
     * detectors, plus per-reason filter counts aggregated over the last hour.
     * Use this to understand why alerts are (or aren't) firing.
     */
    @GetMapping("/detector-thresholds")
    public ResponseEntity<DetectorThresholdsResponse> detectorThresholds() {
        Instant since = clock.now().minus(Duration.ofHours(1));

        PriceMovementDetector.PriceDetectorDiagnostics price = priceDetector.getDiagnostics(since);
        StatisticalAnomalyDetector.StatDetectorDiagnostics stat = statDetector.getDiagnostics(since);
        WalletActivityDetector.WhaleDetectorDiagnostics whale = whaleDetector.getDiagnostics(since);

        // Merge filter counts from price + stat detectors into one map
        Map<String, Long> combined = new HashMap<>(price.filterCountsLastHour());
        stat.filterCountsLastHour().forEach((k, v) -> combined.merge(k, v, Long::sum));

        return ResponseEntity.ok(new DetectorThresholdsResponse(price, stat, whale, combined));
    }
}
