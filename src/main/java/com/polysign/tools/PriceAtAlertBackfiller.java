package com.polysign.tools;

import com.polysign.model.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-off backfill: for PRICE_MOVEMENT alerts where {@code priceAtAlert} is null,
 * reads {@code fromPrice} from the stored metadata and writes it back.
 *
 * <p>Addresses alerts fired before the {@code priceAtAlert} deploy. Statistical-anomaly
 * alerts are NOT backfilled — the pre-spike snapshot price is not reliably stored in
 * metadata for those alerts, so they are left as null and will roll off naturally (~7 days).
 *
 * <h3>Usage</h3>
 * <pre>
 * # Dry-run (default — safe to run; logs what would happen, writes nothing):
 * mvn spring-boot:run -Dspring-boot.run.profiles=backfill
 *
 * # Live run (actually writes to DynamoDB):
 * mvn spring-boot:run -Dspring-boot.run.profiles=backfill \
 *     -Dspring-boot.run.arguments="--dry-run=false"
 * </pre>
 *
 * <p>Writes are idempotent: uses {@code attribute_not_exists(priceAtAlert)} as a
 * condition so that running twice cannot overwrite a correct value.
 *
 * <p>Runs at {@code @Order(2)} — after {@link com.polysign.config.BootstrapRunner}
 * (order=1) has ensured the tables exist.
 */
@Component
@Profile("backfill")
@Order(2)
public class PriceAtAlertBackfiller implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PriceAtAlertBackfiller.class);

    private static final int SAMPLE_LOG_LIMIT = 10;

    private static final Expression NULL_PRICE_AT_ALERT = Expression.builder()
            .expression("attribute_not_exists(priceAtAlert)")
            .build();

    private static final Expression WRITE_IF_STILL_NULL = Expression.builder()
            .expression("attribute_not_exists(priceAtAlert)")
            .build();

    private final DynamoDbTable<Alert> alertsTable;

    public PriceAtAlertBackfiller(DynamoDbTable<Alert> alertsTable) {
        this.alertsTable = alertsTable;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean dryRun = true; // default: safe mode
        List<String> dryRunValues = args.getOptionValues("dry-run");
        if (dryRunValues != null && dryRunValues.contains("false")) {
            dryRun = false;
        }

        log.info("price_at_alert_backfill_start dry_run={}", dryRun);

        int scanned        = 0;
        int wouldUpdate    = 0;
        int skippedNotNull = 0;
        int skippedNoFromPrice = 0;
        List<String> sampleRows = new ArrayList<>();

        List<Alert> nullAlerts = new ArrayList<>();
        try {
            alertsTable.scan(ScanEnhancedRequest.builder()
                            .filterExpression(NULL_PRICE_AT_ALERT)
                            .build())
                    .items()
                    .forEach(nullAlerts::add);
        } catch (Exception e) {
            log.error("price_at_alert_backfill_scan_failed error={}", e.getMessage(), e);
            return;
        }

        for (Alert alert : nullAlerts) {
            scanned++;

            // Guard: this runner targets PRICE_MOVEMENT only
            if (!"price_movement".equals(alert.getType())) {
                skippedNoFromPrice++;
                continue;
            }

            // priceAtAlert is null by scan filter — but double-check in case of race
            if (alert.getPriceAtAlert() != null) {
                skippedNotNull++;
                continue;
            }

            // fromPrice is stored in metadata by PriceMovementDetector
            Map<String, String> meta = alert.getMetadata();
            String fromPriceStr = (meta != null) ? meta.get("fromPrice") : null;
            if (fromPriceStr == null || fromPriceStr.isBlank()) {
                skippedNoFromPrice++;
                log.debug("backfill_skip_no_fromPrice alertId={}", alert.getAlertId());
                continue;
            }

            BigDecimal fromPrice;
            try {
                fromPrice = new BigDecimal(fromPriceStr);
            } catch (NumberFormatException e) {
                skippedNoFromPrice++;
                log.warn("backfill_skip_bad_fromPrice alertId={} raw={}", alert.getAlertId(), fromPriceStr);
                continue;
            }

            wouldUpdate++;

            if (sampleRows.size() < SAMPLE_LOG_LIMIT) {
                sampleRows.add(String.format("alertId=%s createdAt=%s fromPrice=%s",
                        alert.getAlertId(), alert.getCreatedAt(), fromPrice));
            }

            if (!dryRun) {
                alert.setPriceAtAlert(fromPrice);
                try {
                    alertsTable.putItem(PutItemEnhancedRequest.builder(Alert.class)
                            .item(alert)
                            .conditionExpression(WRITE_IF_STILL_NULL)
                            .build());
                    log.debug("backfill_written alertId={} priceAtAlert={}", alert.getAlertId(), fromPrice);
                } catch (ConditionalCheckFailedException e) {
                    log.debug("backfill_skipped_already_set alertId={}", alert.getAlertId());
                } catch (Exception e) {
                    log.warn("backfill_write_failed alertId={} error={}", alert.getAlertId(), e.getMessage());
                }
            }
        }

        log.info("price_at_alert_backfill_complete dry_run={} scanned={} would_update={} skipped_not_null={} skipped_no_fromPrice={}",
                dryRun, scanned, wouldUpdate, skippedNotNull, skippedNoFromPrice);

        if (!sampleRows.isEmpty()) {
            log.info("backfill_sample_rows (up to {}):", SAMPLE_LOG_LIMIT);
            sampleRows.forEach(row -> log.info("  {}", row));
        }

        if (dryRun && wouldUpdate > 0) {
            log.info("DRY RUN complete — rerun with --dry-run=false to apply {} write(s)", wouldUpdate);
        }
    }
}
