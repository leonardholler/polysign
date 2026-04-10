package com.polysign.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.common.AppClock;
import com.polysign.model.Market;
import com.polysign.model.PriceSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SnapshotArchiver}.
 *
 * Uses a testable subclass that overrides scanMarkets(), querySnapshots(), and
 * writeToS3() — no DynamoDB or S3 connection required.
 */
class SnapshotArchiverTest {

    private static final Instant NOW = Instant.parse("2026-04-09T04:00:00Z");

    private SimpleMeterRegistry meterRegistry;
    private Counter writtenCounter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        meterRegistry   = new SimpleMeterRegistry();
        writtenCounter  = Counter.builder("polysign.archive.snapshots.written")
                .register(meterRegistry);
        objectMapper    = new ObjectMapper();
    }

    // ── Test 1: empty day ─────────────────────────────────────────────────────

    @Test
    void emptyDay_noMarkets_zeroWritesAndCounterStaysAtZero() {
        TestableSnapshotArchiver archiver = new TestableSnapshotArchiver(writtenCounter);
        // no markets seeded

        archiver.archive();

        assertThat(archiver.writtenKeys).isEmpty();
        assertThat(writtenCounter.count()).isEqualTo(0.0);
    }

    // ── Test 2: multi-market ──────────────────────────────────────────────────

    @Test
    void multiMarket_threeMarketsWithSnapshots_threeKeysWrittenWithCorrectFormat() {
        TestableSnapshotArchiver archiver = new TestableSnapshotArchiver(writtenCounter);

        archiver.markets.add(market("mkt-001"));
        archiver.markets.add(market("mkt-002"));
        archiver.markets.add(market("mkt-003"));

        archiver.snapshotsByMarket.put("mkt-001", List.of(snap("mkt-001", "0.50")));
        archiver.snapshotsByMarket.put("mkt-002", List.of(snap("mkt-002", "0.60")));
        archiver.snapshotsByMarket.put("mkt-003", List.of(snap("mkt-003", "0.70")));

        archiver.archive();

        assertThat(archiver.writtenKeys).hasSize(3);
        for (String key : archiver.writtenKeys) {
            assertThat(key).matches("snapshots/\\d{4}/\\d{2}/\\d{2}/.+\\.jsonl\\.gz");
        }
        assertThat(writtenCounter.count()).isEqualTo(3.0);
    }

    // ── Test 3: single market with 60 snapshots ───────────────────────────────

    @Test
    void singleMarketSixtySnapshots_fileContainsExactlySixtyLines() throws Exception {
        TestableSnapshotArchiver archiver = new TestableSnapshotArchiver(writtenCounter);
        archiver.markets.add(market("mkt-001"));

        List<PriceSnapshot> snaps = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            snaps.add(snap("mkt-001", "0." + String.format("%02d", i + 10)));
        }
        archiver.snapshotsByMarket.put("mkt-001", snaps);

        archiver.archive();

        assertThat(archiver.writtenKeys).hasSize(1);
        String key = archiver.writtenKeys.get(0);
        byte[] gzipBytes = archiver.writtenData.get(key);

        // Decompress and count newlines
        String content = decompress(gzipBytes);
        long lineCount = content.lines().count();
        assertThat(lineCount).isEqualTo(60);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Market market(String id) {
        Market m = new Market();
        m.setMarketId(id);
        m.setQuestion("Test market?");
        return m;
    }

    private static PriceSnapshot snap(String marketId, String price) {
        PriceSnapshot s = new PriceSnapshot();
        s.setMarketId(marketId);
        s.setTimestamp(NOW.toString());
        s.setMidpoint(new BigDecimal(price));
        s.setYesPrice(new BigDecimal(price));
        s.setNoPrice(BigDecimal.ONE.subtract(new BigDecimal(price)));
        return s;
    }

    private static String decompress(byte[] gzipBytes) throws Exception {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gzipBytes))) {
            return new String(gis.readAllBytes());
        }
    }

    // ── Testable subclass ─────────────────────────────────────────────────────

    private class TestableSnapshotArchiver extends SnapshotArchiver {

        final List<Market> markets = new ArrayList<>();
        final Map<String, List<PriceSnapshot>> snapshotsByMarket = new HashMap<>();
        final List<String> writtenKeys = new ArrayList<>();
        final Map<String, byte[]> writtenData = new HashMap<>();

        TestableSnapshotArchiver(Counter counter) {
            super(fixedClock(), new ObjectMapper(), counter);
        }

        @Override
        List<Market> scanMarkets() {
            return markets;
        }

        @Override
        List<PriceSnapshot> querySnapshots(String marketId, Instant from, Instant to) {
            return snapshotsByMarket.getOrDefault(marketId, List.of());
        }

        @Override
        void writeToS3(String key, byte[] gzipBytes) {
            writtenKeys.add(key);
            writtenData.put(key, gzipBytes);
        }

        private static AppClock fixedClock() {
            AppClock c = new AppClock();
            c.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
            return c;
        }
    }
}
