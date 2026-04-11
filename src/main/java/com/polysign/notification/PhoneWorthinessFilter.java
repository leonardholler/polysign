package com.polysign.notification;

import com.polysign.backtest.SignalPerformanceService;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether an alert deserves a push notification to the user's phone.
 *
 * <p>Three rules, evaluated in order — first match wins:
 * <ol>
 *   <li><b>Consensus auto-pass</b>: {@code type="consensus"} always returns true.
 *       Consensus is the highest-conviction signal in the system.</li>
 *   <li><b>Multi-detector convergence</b>: queries the {@code marketId-createdAt-index}
 *       GSI for all alerts on the same market in the last 15 minutes. If ≥2 distinct
 *       detector types have fired, multiple signals agree — phone worthy.</li>
 *   <li><b>Precision-gated critical</b>: {@code severity="critical"} AND the 7-day
 *       t1h precision for this detector type is ≥ 0.60. Fails closed when precision
 *       is null (no outcome data yet).</li>
 * </ol>
 */
@Component
public class PhoneWorthinessFilter {

    private static final Logger   log                  = LoggerFactory.getLogger(PhoneWorthinessFilter.class);
    private static final Duration CONVERGENCE_WINDOW   = Duration.ofMinutes(15);
    private static final Duration PRECISION_LOOKBACK   = Duration.ofDays(7);
    /** Raise back to 0.60 once price detector precision improves above 50%. */
    private final double precisionThreshold;

    private final DynamoDbTable<Alert>      alertsTable;
    private final SignalPerformanceService  performanceService;
    private final AppClock                 clock;

    public PhoneWorthinessFilter(
            DynamoDbTable<Alert> alertsTable,
            SignalPerformanceService performanceService,
            AppClock clock,
            @Value("${polysign.notification.min-precision:0.60}") double minPrecision) {
        this.alertsTable        = alertsTable;
        this.performanceService = performanceService;
        this.clock              = clock;
        this.precisionThreshold = minPrecision;
    }

    /**
     * Returns {@code true} if this alert should be pushed to the user's phone.
     *
     * <p>Rules evaluated in order — first match wins:
     * <ol>
     *   <li>Consensus auto-pass</li>
     *   <li>Multi-detector convergence (≥2 detector types on same market in 15 min)</li>
     *   <li>Precision-gated critical (severity=critical AND t1h precision ≥ threshold)</li>
     *   <li>Whale trade ≥ $10K (immediate value while backtesting data accumulates)</li>
     *   <li>Price movement entering a resolution zone (ENTERED_BULLISH / ENTERED_BEARISH)</li>
     * </ol>
     */
    public boolean isPhoneWorthy(Alert alert) {
        // Rule (a): consensus always qualifies
        if ("consensus".equals(alert.getType())) {
            return true;
        }

        // Rule (b): multi-detector convergence on the same market in last 15 min
        if (hasMultiDetectorConvergence(alert)) {
            return true;
        }

        // Rule (c): critical + t1h precision gate (fail closed when precision is null)
        if ("critical".equals(alert.getSeverity())) {
            return hasSufficientPrecision(alert.getType());
        }

        // Rule (d): any whale trade >= $10K — bypass precision gate while backtest data accumulates
        if ("wallet_activity".equals(alert.getType())) {
            var meta = alert.getMetadata();
            if (meta != null) {
                String sizeStr = meta.get("sizeUsdc");
                if (sizeStr != null) {
                    try {
                        if (Double.parseDouble(sizeStr) >= 10_000) return true;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // Rule (e): price movement entering a resolution zone
        if ("price_movement".equals(alert.getType())) {
            var meta = alert.getMetadata();
            if (meta != null) {
                String zt = meta.get("zoneTransition");
                if ("ENTERED_BULLISH".equals(zt) || "ENTERED_BEARISH".equals(zt)) return true;
            }
        }

        return false;
    }

    private boolean hasMultiDetectorConvergence(Alert alert) {
        if (alert.getMarketId() == null) return false;
        try {
            List<Alert> recent = queryRecentAlertsForMarket(alert.getMarketId());
            Set<String> distinctTypes = recent.stream()
                    .map(Alert::getType)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            return distinctTypes.size() >= 2;
        } catch (Exception e) {
            log.warn("phone_worthy_convergence_failed marketId={} error={}",
                    alert.getMarketId(), e.getMessage());
            return false;
        }
    }

    /**
     * Queries the {@code marketId-createdAt-index} GSI for alerts on the given market
     * in the last {@link #CONVERGENCE_WINDOW} minutes.
     *
     * <p>Package-private so unit tests can override it without a real DynamoDB connection.
     */
    List<Alert> queryRecentAlertsForMarket(String marketId) {
        DynamoDbIndex<Alert> gsi = alertsTable.index("marketId-createdAt-index");
        String cutoff = clock.now().minus(CONVERGENCE_WINDOW).toString();
        QueryConditional qc = QueryConditional.sortGreaterThanOrEqualTo(
                Key.builder().partitionValue(marketId).sortValue(cutoff).build());
        List<Alert> result = new ArrayList<>();
        gsi.query(r -> r.queryConditional(qc))
           .stream()
           .flatMap(page -> page.items().stream())
           .forEach(result::add);
        return result;
    }

    private boolean hasSufficientPrecision(String type) {
        if (type == null) return false;
        try {
            var resp = performanceService.getPerformance(
                    type, "t1h", clock.now().minus(PRECISION_LOOKBACK));
            return resp.detectors().stream()
                    .filter(d -> type.equals(d.type()))
                    .findFirst()
                    .map(d -> d.precision() != null && d.precision() >= precisionThreshold)
                    .orElse(false);
        } catch (Exception e) {
            log.warn("phone_worthy_precision_failed type={} error={}", type, e.getMessage());
            return false;
        }
    }
}
