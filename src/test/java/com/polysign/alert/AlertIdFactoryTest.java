package com.polysign.alert;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

class AlertIdFactoryTest {

    private static final Duration THIRTY_MINUTES = Duration.ofMinutes(30);

    // ── Determinism ──────────────────────────────────────────────────────────

    @Test
    void sameInputsProduceSameId() {
        Instant now = Instant.parse("2026-04-09T12:00:00Z");
        String id1 = AlertIdFactory.generate("price_movement", "market-1", now, THIRTY_MINUTES);
        String id2 = AlertIdFactory.generate("price_movement", "market-1", now, THIRTY_MINUTES);
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void sameInputsWithinSameDedupeWindowProduceSameId() {
        // Two instants 10 minutes apart — both fall in the same 30-minute bucket
        Instant t1 = Instant.parse("2026-04-09T12:05:00Z");
        Instant t2 = Instant.parse("2026-04-09T12:15:00Z");
        String id1 = AlertIdFactory.generate("price_movement", "market-1", t1, THIRTY_MINUTES);
        String id2 = AlertIdFactory.generate("price_movement", "market-1", t2, THIRTY_MINUTES);
        assertThat(id1).isEqualTo(id2);
    }

    // ── Sensitivity to each input field ──────────────────────────────────────

    @Test
    void differentTypeProducesDifferentId() {
        Instant now = Instant.parse("2026-04-09T12:00:00Z");
        String id1 = AlertIdFactory.generate("price_movement", "market-1", now, THIRTY_MINUTES);
        String id2 = AlertIdFactory.generate("statistical_anomaly", "market-1", now, THIRTY_MINUTES);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void differentMarketIdProducesDifferentId() {
        Instant now = Instant.parse("2026-04-09T12:00:00Z");
        String id1 = AlertIdFactory.generate("price_movement", "market-1", now, THIRTY_MINUTES);
        String id2 = AlertIdFactory.generate("price_movement", "market-2", now, THIRTY_MINUTES);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void differentBucketedTimestampProducesDifferentId() {
        // Two instants in different 30-minute buckets
        Instant t1 = Instant.parse("2026-04-09T12:00:00Z");
        Instant t2 = Instant.parse("2026-04-09T12:30:00Z");
        String id1 = AlertIdFactory.generate("price_movement", "market-1", t1, THIRTY_MINUTES);
        String id2 = AlertIdFactory.generate("price_movement", "market-1", t2, THIRTY_MINUTES);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void differentPayloadHashProducesDifferentId() {
        Instant now = Instant.parse("2026-04-09T12:00:00Z");
        String id1 = AlertIdFactory.generate("price_movement", "market-1", now, THIRTY_MINUTES, "payload-a");
        String id2 = AlertIdFactory.generate("price_movement", "market-1", now, THIRTY_MINUTES, "payload-b");
        assertThat(id1).isNotEqualTo(id2);
    }

    // ── Dedupe bypass ────────────────────────────────────────────────────────

    @Test
    void zeroWindowDisablesBucketing() {
        Instant t1 = Instant.parse("2026-04-09T12:05:00Z");
        Instant t2 = Instant.parse("2026-04-09T12:05:01Z"); // 1 second later
        String id1 = AlertIdFactory.generate("price_movement", "market-1", t1, Duration.ZERO);
        String id2 = AlertIdFactory.generate("price_movement", "market-1", t2, Duration.ZERO);
        assertThat(id1).isNotEqualTo(id2);
    }

    // ── Output format ────────────────────────────────────────────────────────

    @Test
    void outputIsLowercaseHexSha256() {
        String id = AlertIdFactory.generate("price_movement", "market-1",
                Instant.parse("2026-04-09T12:00:00Z"), THIRTY_MINUTES);
        assertThat(id).hasSize(64);
        assertThat(id).matches("[0-9a-f]{64}");
    }

    // ── Collision resistance ─────────────────────────────────────────────────

    @Test
    void tenThousandRandomInputsProduceNoCollisions() {
        Set<String> ids = new HashSet<>(10_000);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String[] types = {"price_movement", "statistical_anomaly", "consensus", "news_correlation"};

        for (int i = 0; i < 10_000; i++) {
            String type = types[rng.nextInt(types.length)];
            String marketId = UUID.randomUUID().toString();
            Instant timestamp = Instant.ofEpochSecond(rng.nextLong(1_700_000_000L, 1_800_000_000L));
            ids.add(AlertIdFactory.generate(type, marketId, timestamp, THIRTY_MINUTES));
        }

        assertThat(ids).hasSize(10_000);
    }

    // ── bucketTimestamp internals ─────────────────────────────────────────────

    @Test
    void bucketTimestampRoundsDownToWindowBoundary() {
        // 12:17:00 with a 30-minute window should bucket to 12:00:00
        Instant t = Instant.parse("2026-04-09T12:17:00Z");
        long bucketed = AlertIdFactory.bucketTimestamp(t, THIRTY_MINUTES);
        assertThat(bucketed).isEqualTo(Instant.parse("2026-04-09T12:00:00Z").getEpochSecond());
    }

    @Test
    void bucketTimestampOnBoundaryStaysOnBoundary() {
        Instant t = Instant.parse("2026-04-09T12:30:00Z");
        long bucketed = AlertIdFactory.bucketTimestamp(t, THIRTY_MINUTES);
        assertThat(bucketed).isEqualTo(t.getEpochSecond());
    }

    @Test
    void bucketTimestampWithZeroDurationReturnsRawEpoch() {
        Instant t = Instant.parse("2026-04-09T12:17:42Z");
        long bucketed = AlertIdFactory.bucketTimestamp(t, Duration.ZERO);
        assertThat(bucketed).isEqualTo(t.getEpochSecond());
    }
}
