package com.polysign.admin;

import com.polysign.model.AlertOutcome;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-shot admin endpoint that backfills skill-metric fields onto existing alert_outcomes rows.
 *
 * <p>Old rows lack {@code scorable}, {@code deadZone}, and {@code brierSkill}. This endpoint
 * reads every outcome row, recomputes those fields from the stored {@code priceAtAlert},
 * {@code priceAtHorizon}, and {@code directionPredicted}, then writes the row back.
 *
 * <p>Auth: {@code X-Admin-Key} header must match the {@code ADMIN_EXPORT_KEY} env var.
 *
 * <p>Usage:
 * <pre>{@code curl -X POST -H "X-Admin-Key: $ADMIN_EXPORT_KEY" .../admin/backfill/outcomes}</pre>
 *
 * <p>Idempotent — can be re-run safely. Each write is unconditional (replaces the full row).
 */
@RestController
@RequestMapping("/admin/backfill")
public class BackfillController {

    private static final Logger log = LoggerFactory.getLogger(BackfillController.class);

    private final DynamoDbTable<AlertOutcome> alertOutcomesTable;
    private final String                      adminExportKey;

    public BackfillController(
            DynamoDbTable<AlertOutcome> alertOutcomesTable,
            @Value("${ADMIN_EXPORT_KEY:}") String adminExportKey) {
        this.alertOutcomesTable = alertOutcomesTable;
        this.adminExportKey     = adminExportKey;
    }

    @PostMapping("/outcomes")
    public Map<String, Long> backfillOutcomes(
            @RequestHeader(value = "X-Admin-Key", required = false) String providedKey,
            HttpServletResponse response) {

        if (adminExportKey.isBlank() || !adminExportKey.equals(providedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return Map.of("error", -1L);
        }

        List<AlertOutcome> rows = new ArrayList<>();
        alertOutcomesTable.scan().items().forEach(rows::add);

        long updated = 0;
        long skipped = 0;
        long errors  = 0;

        for (AlertOutcome o : rows) {
            try {
                boolean changed = backfillRow(o);
                if (changed) {
                    alertOutcomesTable.putItem(o);
                    updated++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("backfill_outcome_failed alertId={} horizon={} error={}",
                        o.getAlertId(), o.getHorizon(), e.getMessage());
                errors++;
            }
        }

        log.info("backfill_outcomes_complete total={} updated={} skipped={} errors={}",
                rows.size(), updated, skipped, errors);
        return Map.of("total", (long) rows.size(), "updated", updated,
                "skipped", skipped, "errors", errors);
    }

    /**
     * Recomputes {@code scorable}, {@code skipReason}, {@code deadZone},
     * {@code marketBrier}, {@code detectorBrier}, and {@code brierSkill}
     * from the stored {@code priceAtAlert}, {@code priceAtHorizon}, and
     * {@code directionPredicted}.
     *
     * @return true if any field was changed
     */
    static boolean backfillRow(AlertOutcome o) {
        BigDecimal priceAtAlert   = o.getPriceAtAlert();
        BigDecimal priceAtHorizon = o.getPriceAtHorizon();
        String     direction      = o.getDirectionPredicted();

        boolean isUnscorable = (priceAtAlert == null
                || priceAtAlert.compareTo(BigDecimal.ZERO) == 0);

        if (isUnscorable) {
            // Only update if something changed
            if (!Boolean.FALSE.equals(o.getScorable())
                    || !"no_baseline".equals(o.getSkipReason())) {
                o.setScorable(false);
                o.setSkipReason("no_baseline");
                o.setDeadZone(null);
                o.setMarketBrier(null);
                o.setDetectorBrier(null);
                o.setBrierSkill(null);
                return true;
            }
            return false;
        }

        boolean dz = priceAtAlert.compareTo(BigDecimal.valueOf(0.10)) < 0
                  || priceAtAlert.compareTo(BigDecimal.valueOf(0.90)) > 0;

        BigDecimal mb = null, db = null, bs = null;
        if (direction != null && priceAtHorizon != null) {
            double actual    = priceAtHorizon.doubleValue() >= 0.50 ? 1.0 : 0.0;
            double mktProb   = priceAtAlert.doubleValue();
            double detProb   = "up".equals(direction)
                    ? Math.min(mktProb + 0.20, 0.99)
                    : Math.max(mktProb - 0.20, 0.01);
            double mbD = (mktProb - actual) * (mktProb - actual);
            double dbD = (detProb  - actual) * (detProb  - actual);
            mb = BigDecimal.valueOf(mbD).setScale(6, RoundingMode.HALF_UP);
            db = BigDecimal.valueOf(dbD).setScale(6, RoundingMode.HALF_UP);
            bs = BigDecimal.valueOf(mbD - dbD).setScale(6, RoundingMode.HALF_UP);
        }

        boolean changed = !Boolean.TRUE.equals(o.getScorable())
                || !Boolean.valueOf(dz).equals(o.getDeadZone())
                || !equalsBigDecimal(mb, o.getMarketBrier())
                || !equalsBigDecimal(db, o.getDetectorBrier())
                || !equalsBigDecimal(bs, o.getBrierSkill());

        if (changed) {
            o.setScorable(true);
            o.setSkipReason(null);
            o.setDeadZone(dz);
            o.setMarketBrier(mb);
            o.setDetectorBrier(db);
            o.setBrierSkill(bs);
        }
        return changed;
    }

    private static boolean equalsBigDecimal(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }
}
