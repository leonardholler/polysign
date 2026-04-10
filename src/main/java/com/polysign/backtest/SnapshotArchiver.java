package com.polysign.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.common.AppClock;
import com.polysign.model.Market;
import com.polysign.model.PriceSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Daily snapshot archiver — runs at 04:00 UTC and writes the previous 24 hours
 * of price snapshots to S3 as gzipped JSON-Lines files.
 *
 * <p>One file per tracked market:
 * {@code s3://polysign-archives/snapshots/{yyyy}/{MM}/{dd}/{marketId}.jsonl.gz}
 *
 * <p>S3 PutObject is naturally idempotent on the same key — no head-check needed.
 *
 * <p>Package-private methods ({@link #scanMarkets}, {@link #querySnapshots},
 * {@link #writeToS3}) are overridable in tests via a subclass — no DynamoDB or
 * S3 connection required for unit tests.
 */
@Component
public class SnapshotArchiver {

    private static final Logger log = LoggerFactory.getLogger(SnapshotArchiver.class);

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private final DynamoDbTable<Market>       marketsTable;
    private final DynamoDbTable<PriceSnapshot> snapshotsTable;
    private final S3Client                     s3Client;
    private final ObjectMapper                 objectMapper;
    private final AppClock                     clock;
    private final Counter                      writtenCounter;
    private final String                       archivesBucket;

    @org.springframework.beans.factory.annotation.Autowired
    public SnapshotArchiver(
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<PriceSnapshot> snapshotsTable,
            S3Client s3Client,
            ObjectMapper objectMapper,
            AppClock clock,
            MeterRegistry meterRegistry,
            @Value("${polysign.s3.archives-bucket:polysign-archives}") String archivesBucket) {
        this.marketsTable   = marketsTable;
        this.snapshotsTable = snapshotsTable;
        this.s3Client       = s3Client;
        this.objectMapper   = objectMapper;
        this.clock          = clock;
        this.archivesBucket = archivesBucket;
        this.writtenCounter = Counter.builder("polysign.archive.snapshots.written")
                .description("Number of market snapshot archives written to S3")
                .register(meterRegistry);
    }

    // Test constructor — DynamoDB/S3/archivesBucket are irrelevant because subclass overrides them.
    SnapshotArchiver(AppClock clock, ObjectMapper objectMapper, Counter writtenCounter) {
        this.marketsTable   = null;
        this.snapshotsTable = null;
        this.s3Client       = null;
        this.objectMapper   = objectMapper;
        this.clock          = clock;
        this.writtenCounter = writtenCounter;
        this.archivesBucket = "test-bucket";
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void run() {
        try {
            archive();
        } catch (Exception e) {
            log.error("snapshot_archiver_failed", e);
        }
    }

    /**
     * Core archive loop — package-private for direct invocation in tests.
     */
    void archive() {
        Instant now  = clock.now();
        Instant from = now.minus(Duration.ofHours(24));
        String datePath = DATE_FMT.format(now);

        List<Market> markets = scanMarkets();
        int written = 0;

        for (Market market : markets) {
            try {
                List<PriceSnapshot> snapshots = querySnapshots(market.getMarketId(), from, now);
                if (snapshots.isEmpty()) {
                    log.debug("snapshot_archive_empty marketId={} date={}", market.getMarketId(), datePath);
                    continue;
                }

                byte[] gzipBytes = toGzipJsonLines(snapshots);
                String key = "snapshots/" + datePath + "/" + market.getMarketId() + ".jsonl.gz";
                writeToS3(key, gzipBytes);
                writtenCounter.increment();
                written++;

                log.debug("snapshot_archived marketId={} snapshots={} key={}",
                        market.getMarketId(), snapshots.size(), key);
            } catch (Exception e) {
                log.warn("snapshot_archive_market_failed marketId={} error={}",
                        market.getMarketId(), e.getMessage());
            }
        }

        log.info("snapshot_archive_complete date={} markets_written={} of={}",
                datePath, written, markets.size());
    }

    private byte[] toGzipJsonLines(List<PriceSnapshot> snapshots) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            for (PriceSnapshot snap : snapshots) {
                gzip.write(objectMapper.writeValueAsBytes(snap));
                gzip.write('\n');
            }
        }
        return baos.toByteArray();
    }

    // ── Package-private seams (overridden in tests) ───────────────────────────

    List<Market> scanMarkets() {
        List<Market> result = new ArrayList<>();
        marketsTable.scan().items().forEach(result::add);
        return result;
    }

    List<PriceSnapshot> querySnapshots(String marketId, Instant from, Instant to) {
        var qc = QueryConditional.sortBetween(
                Key.builder().partitionValue(marketId).sortValue(from.toString()).build(),
                Key.builder().partitionValue(marketId).sortValue(to.toString()).build());

        return snapshotsTable.query(r -> r.queryConditional(qc).scanIndexForward(true))
                .items()
                .stream()
                .toList();
    }

    void writeToS3(String key, byte[] gzipBytes) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(archivesBucket)
                        .key(key)
                        .contentType("application/gzip")
                        .build(),
                RequestBody.fromBytes(gzipBytes));
        log.debug("s3_put_complete bucket={} key={} bytes={}", archivesBucket, key, gzipBytes.length);
    }
}
