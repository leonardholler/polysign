package com.polysign.api;

import com.polysign.backtest.SignalPerformanceService;
import com.polysign.backtest.SignalPerformanceService.AggregatePrecision;
import com.polysign.common.AppClock;
import com.polysign.common.AppStats;
import com.polysign.model.Alert;
import com.polysign.model.WatchedWallet;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StatsController}.
 *
 * Verifies that the new signalPrecision7d1h / scoredSamples7d1h fields are
 * populated correctly from SignalPerformanceService.getAggregatePrecision().
 */
@ExtendWith(MockitoExtension.class)
class StatsControllerTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<Alert> alertsTable;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<WatchedWallet> watchedWalletsTable;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    MeterRegistry meterRegistry;

    @Mock
    AppStats appStats;

    @Mock
    SignalPerformanceService signalPerformanceService;

    AppClock clock;
    StatsController controller;

    @BeforeEach
    void setUp() {
        clock = new AppClock();
        clock.setClock(Clock.fixed(Instant.parse("2026-04-09T12:00:00Z"), ZoneOffset.UTC));

        // stub DynamoDB scans to return empty streams
        when(alertsTable.scan().items().stream()).thenReturn(Stream.of());
        when(watchedWalletsTable.scan().items().stream()).thenReturn(Stream.of());

        // stub MeterRegistry gauge lookup to return null (no gauge registered)
        when(meterRegistry.find(any()).gauge()).thenReturn(null);

        controller = new StatsController(
                alertsTable, watchedWalletsTable, meterRegistry,
                appStats, clock, "test-topic", signalPerformanceService);
    }

    // ── Test 1: precision fields populated when scored samples exist ──────────

    @Test
    void precisionFieldsPopulated_whenScoredSamplesExist() {
        when(signalPerformanceService.getAggregatePrecision(eq("t1h"), any()))
                .thenReturn(new AggregatePrecision(0.583, 24L));
        when(signalPerformanceService.getAggregatePrecision(eq("t15m"), any()))
                .thenReturn(new AggregatePrecision(0.612, 30L));

        StatsController.StatsResponse resp = controller.getStats();

        assertThat(resp.signalPrecision7d1h()).isNotNull();
        assertThat(resp.signalPrecision7d1h()).isCloseTo(0.583, within(1e-9));
        assertThat(resp.scoredSamples7d1h()).isEqualTo(24L);

        assertThat(resp.signalPrecision7d15m()).isNotNull();
        assertThat(resp.signalPrecision7d15m()).isCloseTo(0.612, within(1e-9));
        assertThat(resp.scoredSamples7d15m()).isEqualTo(30L);
    }

    // ── Test 2: precision null + samples 0 when scorer table is empty ─────────

    @Test
    void precisionNull_andSamplesZero_whenNoScoredOutcomes() {
        when(signalPerformanceService.getAggregatePrecision(any(), any()))
                .thenReturn(new AggregatePrecision(null, 0L));

        StatsController.StatsResponse resp = controller.getStats();

        assertThat(resp.signalPrecision7d1h()).isNull();
        assertThat(resp.scoredSamples7d1h()).isEqualTo(0L);

        assertThat(resp.signalPrecision7d15m()).isNull();
        assertThat(resp.scoredSamples7d15m()).isEqualTo(0L);
    }
}
