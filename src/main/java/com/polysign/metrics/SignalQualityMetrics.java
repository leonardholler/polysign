package com.polysign.metrics;

import com.polysign.backtest.SignalPerformanceService;
import com.polysign.common.AppClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes signal quality Micrometer gauges, refreshed every 5 minutes.
 *
 * <p>Gauges are registered once at startup against backing {@link ConcurrentHashMap}s
 * and updated by the scheduler. The update reads live data from
 * {@link SignalPerformanceService} (which queries the {@code type-firedAt-index} GSI),
 * so the gauges reflect the last 7 days of evaluated outcomes.
 *
 * <p>Metrics emitted (tags: {@code type}, {@code horizon}):
 * <ul>
 *   <li>{@code polysign.signals.precision} — fraction of alerts with correct direction
 *       (null → NaN when no samples; NaN is valid in Prometheus)</li>
 *   <li>{@code polysign.signals.magnitude.mean} — mean signed magnitude (pp)</li>
 *   <li>{@code polysign.signals.sample.count} — number of evaluated outcomes</li>
 * </ul>
 *
 * <p>Horizons covered: {@code t1h} and {@code t24h}. Resolution horizon is excluded
 * because the sample size is too small on new deployments to be meaningful as a gauge.
 */
@Component
public class SignalQualityMetrics {

    private static final Logger log = LoggerFactory.getLogger(SignalQualityMetrics.class);

    private static final List<String> HORIZONS = List.of("t1h", "t24h");

    private final SignalPerformanceService performanceService;
    private final AppClock                 clock;

    // Backing maps — key is "type|horizon"
    private final ConcurrentHashMap<String, Double> precisionMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> magnitudeMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> sampleMap    = new ConcurrentHashMap<>();

    public SignalQualityMetrics(
            SignalPerformanceService performanceService,
            AppClock clock,
            MeterRegistry meterRegistry) {

        this.performanceService = performanceService;
        this.clock              = clock;

        // Register one gauge per known detector type × horizon combination.
        // The gauge supplier reads from the backing map, which the scheduler updates.
        for (String type : SignalPerformanceService.KNOWN_TYPES) {
            for (String horizon : HORIZONS) {
                final String key = type + "|" + horizon;

                Gauge.builder("polysign.signals.precision", precisionMap,
                                m -> m.getOrDefault(key, Double.NaN))
                        .tag("type", type)
                        .tag("horizon", horizon)
                        .description("Fraction of alerts with correct direction prediction "
                                + "(flat outcomes excluded; NaN when no samples)")
                        .register(meterRegistry);

                Gauge.builder("polysign.signals.magnitude.mean", magnitudeMap,
                                m -> m.getOrDefault(key, 0.0))
                        .tag("type", type)
                        .tag("horizon", horizon)
                        .description("Mean signed magnitude (pp) of alert outcomes")
                        .register(meterRegistry);

                Gauge.builder("polysign.signals.sample.count", sampleMap,
                                m -> m.getOrDefault(key, 0.0))
                        .tag("type", type)
                        .tag("horizon", horizon)
                        .description("Number of evaluated alert outcomes in the last 7 days")
                        .register(meterRegistry);
            }
        }
    }

    /**
     * Refreshes signal quality gauges every 5 minutes (offset 1 minute after
     * AlertOutcomeEvaluator's cron so fresh outcomes are already written).
     */
    @Scheduled(cron = "0 1/5 * * * *")
    public void refresh() {
        Instant since = clock.now().minus(Duration.ofDays(7));
        try {
            for (String horizon : HORIZONS) {
                var response = performanceService.getPerformance(null, horizon, since);
                for (var det : response.detectors()) {
                    String key = det.type() + "|" + horizon;
                    precisionMap.put(key, det.precision() != null ? det.precision() : Double.NaN);
                    magnitudeMap.put(key, det.avgMagnitudePp());
                    sampleMap.put(key,   (double) det.count());
                }
            }
            log.debug("signal_quality_metrics_refreshed horizon_count={}", HORIZONS.size());
        } catch (Exception e) {
            log.warn("signal_quality_metrics_refresh_failed error={}", e.getMessage());
        }
    }
}
