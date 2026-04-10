package com.polysign.api;

import com.polysign.backtest.SignalPerformanceService;
import com.polysign.backtest.SignalPerformanceService.PerformanceResponse;
import com.polysign.common.AppClock;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * REST controller for signal quality metrics.
 *
 * GET /api/signals/performance?type=&horizon=&since=
 *
 * Query params:
 *   type    optional; one of price_movement, statistical_anomaly, consensus,
 *           wallet_activity, news_correlation
 *   horizon optional; one of t15m, t1h, t24h, resolution (default: t1h)
 *   since   optional; ISO-8601 instant (default: 7 days ago)
 *
 * Returns RFC 7807 application/problem+json on validation errors.
 */
@RestController
@RequestMapping("/api/signals")
public class SignalPerformanceController {

    private static final Set<String> VALID_TYPES = Set.of(
            "price_movement", "statistical_anomaly", "consensus",
            "wallet_activity", "news_correlation");

    private static final Set<String> VALID_HORIZONS = Set.of("t15m", "t1h", "t24h", "resolution");

    private final SignalPerformanceService service;
    private final AppClock clock;

    public SignalPerformanceController(SignalPerformanceService service, AppClock clock) {
        this.service = service;
        this.clock   = clock;
    }

    @GetMapping("/performance")
    public ResponseEntity<PerformanceResponse> getPerformance(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String horizon,
            @RequestParam(required = false) String since) {

        // Validate type
        if (type != null && !VALID_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid type '" + type + "'. Must be one of: " + VALID_TYPES);
        }

        // Validate horizon
        String resolvedHorizon = (horizon == null || horizon.isBlank()) ? "t1h" : horizon;
        if (!VALID_HORIZONS.contains(resolvedHorizon)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid horizon '" + horizon + "'. Must be one of: " + VALID_HORIZONS);
        }

        // Parse since
        Instant resolvedSince;
        if (since == null || since.isBlank()) {
            resolvedSince = clock.now().minus(Duration.ofDays(7));
        } else {
            try {
                resolvedSince = Instant.parse(since);
            } catch (DateTimeParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid 'since' parameter '" + since + "'. Must be ISO-8601 instant (e.g. 2026-04-01T00:00:00Z)");
            }
        }

        PerformanceResponse response = service.getPerformance(type, resolvedHorizon, resolvedSince);
        return ResponseEntity.ok(response);
    }
}
