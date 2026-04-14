package com.polysign.api.v1;

import com.polysign.backtest.SignalPerformanceService;
import com.polysign.backtest.SignalPerformanceService.PerformanceResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SignalsV1ControllerTest {

    @Test
    void delegatesToSignalPerformanceService() {
        SignalPerformanceService svc = mock(SignalPerformanceService.class);
        PerformanceResponse expected = new PerformanceResponse("t1h", "2026-01-01T00:00:00Z", List.of(), 0, 0);
        when(svc.getPerformance(any(), any(), any())).thenReturn(expected);

        SignalsV1Controller controller = new SignalsV1Controller(svc);
        PerformanceResponse result = controller.performance(null, null, null);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void withHorizonAndType_passesParamsToService() {
        SignalPerformanceService svc = mock(SignalPerformanceService.class);
        PerformanceResponse resp = new PerformanceResponse("t15m", "2026-01-01T00:00:00Z", List.of(), 0, 0);
        when(svc.getPerformance(eq("price_movement"), eq("t15m"), any())).thenReturn(resp);

        SignalsV1Controller controller = new SignalsV1Controller(svc);
        PerformanceResponse result = controller.performance("t15m", "price_movement", null);

        assertThat(result.horizon()).isEqualTo("t15m");
    }

    @Test
    void withValidSince_parsesAndPassesToService() {
        SignalPerformanceService svc = mock(SignalPerformanceService.class);
        PerformanceResponse resp = new PerformanceResponse("t1h", "2026-01-01T00:00:00Z", List.of(), 0, 0);
        when(svc.getPerformance(any(), any(), any())).thenReturn(resp);

        SignalsV1Controller controller = new SignalsV1Controller(svc);
        controller.performance(null, null, "2026-01-01T00:00:00Z");

        verify(svc).getPerformance(eq(null), eq(null), eq(java.time.Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    void withInvalidSince_fallsBackToNull() {
        SignalPerformanceService svc = mock(SignalPerformanceService.class);
        when(svc.getPerformance(any(), any(), any()))
                .thenReturn(new PerformanceResponse("t1h", "x", List.of(), 0, 0));

        SignalsV1Controller controller = new SignalsV1Controller(svc);
        controller.performance(null, null, "not-a-date");

        // Invalid since → sinceInstant=null passed to service (it applies 7-day default)
        verify(svc).getPerformance(null, null, null);
    }
}
