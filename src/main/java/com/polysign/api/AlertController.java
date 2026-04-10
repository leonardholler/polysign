package com.polysign.api;

import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST endpoints for the alert feed.
 *
 * <ul>
 *   <li>GET /api/alerts?limit=100&severity=&type=&since=</li>
 *   <li>GET /api/alerts/by-signal-strength</li>
 *   <li>POST /api/alerts/{alertId}/mark-reviewed</li>
 * </ul>
 *
 * <p>Both list endpoints enrich each alert with denormalized market context
 * (question, currentYesPrice, volume24h) loaded via a batch Market table lookup —
 * never one query per alert. Signal strength (distinct detector types on the same
 * market in the last 60 minutes) is computed in a single pass over the recent alerts.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private static final Logger log = LoggerFactory.getLogger(AlertController.class);

    private static final int    DEFAULT_LIMIT     = 100;
    private static final int    MAX_LIMIT         = 500;
    private static final int    SIGNAL_WINDOW_MIN = 60;
    private static final int    BADGE_THRESHOLD   = 3;

    private final DynamoDbTable<Alert>  alertsTable;
    private final DynamoDbTable<Market> marketsTable;
    private final DynamoDbEnhancedClient enhancedClient;
    private final AppClock              clock;

    public AlertController(
            DynamoDbTable<Alert> alertsTable,
            DynamoDbTable<Market> marketsTable,
            DynamoDbEnhancedClient enhancedClient,
            AppClock clock) {
        this.alertsTable    = alertsTable;
        this.marketsTable   = marketsTable;
        this.enhancedClient = enhancedClient;
        this.clock          = clock;
    }

    // ── GET /api/alerts ───────────────────────────────────────────────────────

    @GetMapping
    public List<AlertDto> listAlerts(
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String since) {

        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT);
        }

        String effectiveSince = parseSince(since, Duration.ofDays(7));
        String window60m = clock.now().minus(Duration.ofMinutes(SIGNAL_WINDOW_MIN)).toString();

        // Single scan — derive signal strengths from the 60-min subset, then filter
        List<Alert> all = alertsTable.scan().items().stream().toList();

        Map<String, Integer> signalStrengths = computeSignalStrengths(all, window60m);

        List<Alert> filtered = all.stream()
                .filter(a -> a.getCreatedAt() != null
                        && a.getCreatedAt().compareTo(effectiveSince) >= 0)
                .filter(a -> severity == null || severity.equals(a.getSeverity()))
                .filter(a -> type == null || type.equals(a.getType()))
                .sorted(Comparator.comparing(Alert::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();

        Map<String, Market> markets = batchLoadMarkets(filtered);

        return filtered.stream()
                .map(a -> toDto(a, markets.get(a.getMarketId()),
                        signalStrengths.getOrDefault(a.getMarketId(), 1)))
                .toList();
    }

    // ── GET /api/alerts/by-signal-strength ────────────────────────────────────

    @GetMapping("/by-signal-strength")
    public List<AlertDto> bySignalStrength() {
        String window60m = clock.now().minus(Duration.ofMinutes(SIGNAL_WINDOW_MIN)).toString();

        List<Alert> recent = alertsTable.scan().items().stream()
                .filter(a -> a.getCreatedAt() != null
                        && a.getCreatedAt().compareTo(window60m) >= 0)
                .toList();

        Map<String, Integer> signalStrengths = computeSignalStrengths(recent, window60m);

        Map<String, Market> markets = batchLoadMarkets(recent);

        return recent.stream()
                .map(a -> toDto(a, markets.get(a.getMarketId()),
                        signalStrengths.getOrDefault(a.getMarketId(), 1)))
                .sorted(Comparator
                        .comparingInt(AlertDto::signalStrength).reversed()
                        .thenComparing(AlertDto::createdAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // ── POST /api/alerts/{alertId}/mark-reviewed ─────────────────────────────

    @PostMapping("/{alertId}/mark-reviewed")
    public ResponseEntity<Void> markReviewed(@PathVariable String alertId) {
        var pages = alertsTable.query(QueryConditional.keyEqualTo(
                Key.builder().partitionValue(alertId).build()));
        var it = pages.items().iterator();
        if (!it.hasNext()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Alert not found: " + alertId);
        }
        Alert alert = it.next();
        alert.setReviewed(true);
        alertsTable.updateItem(alert);
        log.info("alert_reviewed alertId={}", alertId);
        return ResponseEntity.ok().build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Computes the number of distinct detector types that fired on each market
     * within the provided {@code window60m} ISO-8601 cutoff.
     */
    private Map<String, Integer> computeSignalStrengths(List<Alert> alerts, String window60m) {
        Map<String, Set<String>> marketTypes = new HashMap<>();
        for (Alert a : alerts) {
            if (a.getCreatedAt() == null || a.getMarketId() == null || a.getType() == null) continue;
            if (a.getCreatedAt().compareTo(window60m) >= 0) {
                marketTypes.computeIfAbsent(a.getMarketId(), k -> new HashSet<>()).add(a.getType());
            }
        }
        return marketTypes.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
    }

    /**
     * Batch-loads Market rows for all unique marketIds referenced in the alert list.
     * Uses DynamoDB Enhanced Client batchGetItem — one API call (or a small number of
     * paged calls for >100 markets) rather than one GetItem per alert.
     */
    private Map<String, Market> batchLoadMarkets(List<Alert> alerts) {
        Set<String> uniqueIds = alerts.stream()
                .map(Alert::getMarketId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (uniqueIds.isEmpty()) return Map.of();

        Map<String, Market> result = new HashMap<>();
        // DynamoDB batchGetItem allows max 100 items per call; split if needed.
        List<String> idList = new ArrayList<>(uniqueIds);
        for (int i = 0; i < idList.size(); i += 100) {
            List<String> chunk = idList.subList(i, Math.min(i + 100, idList.size()));
            ReadBatch.Builder<Market> batchBuilder = ReadBatch.builder(Market.class)
                    .mappedTableResource(marketsTable);
            for (String id : chunk) {
                batchBuilder.addGetItem(Key.builder().partitionValue(id).build());
            }
            try {
                enhancedClient.batchGetItem(r -> r.readBatches(batchBuilder.build()))
                        .resultsForTable(marketsTable)
                        .forEach(m -> result.put(m.getMarketId(), m));
            } catch (Exception e) {
                log.warn("alert_market_batch_load_failed error={}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * Extracts the most informative metadata value for a quick inline display.
     */
    private String metadataHighlight(Alert alert) {
        Map<String, String> meta = alert.getMetadata();
        if (meta == null) return null;
        return switch (alert.getType() != null ? alert.getType() : "") {
            case "price_movement" -> {
                String pct = meta.get("movePct");
                String dir = meta.get("direction");
                if (pct == null) yield null;
                yield ("up".equalsIgnoreCase(dir) ? "+" : "−") + pct + "%";
            }
            case "statistical_anomaly" -> {
                String z = meta.get("zScore");
                yield z != null ? "z=" + z : null;
            }
            case "consensus" -> {
                String wc = meta.get("walletCount");
                yield wc != null ? wc + " wallets" : null;
            }
            case "news_correlation" -> {
                String score = meta.get("score");
                yield score != null ? "score=" + score : null;
            }
            case "wallet_activity" -> {
                String size = meta.get("sizeUsdc");
                if (size == null) yield null;
                try {
                    double val = Double.parseDouble(size);
                    yield String.format("$%,.0f", val);
                } catch (NumberFormatException e) {
                    yield "$" + size;
                }
            }
            default -> null;
        };
    }

    private AlertDto toDto(Alert alert, Market market, int signalStrength) {
        String question = market != null && market.getQuestion() != null
                ? truncate(market.getQuestion(), 120)
                : null;
        return new AlertDto(
                alert.getAlertId(),
                alert.getCreatedAt(),
                alert.getType(),
                alert.getSeverity(),
                alert.getMarketId(),
                alert.getTitle(),
                alert.getDescription(),
                alert.getMetadata(),
                alert.getWasNotified(),
                alert.getPhoneWorthy(),
                alert.getReviewed(),
                alert.getLink(),
                question,
                market != null ? market.getCurrentYesPrice() : null,
                market != null ? market.getVolume24h() : null,
                signalStrength,
                signalStrength >= BADGE_THRESHOLD,
                metadataHighlight(alert));
    }

    private String parseSince(String since, Duration defaultLookback) {
        if (since == null || since.isBlank()) {
            return clock.now().minus(defaultLookback).toString();
        }
        try {
            return java.time.Instant.parse(since).toString();
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid 'since' parameter '" + since + "'. Must be ISO-8601 (e.g. 2026-04-01T00:00:00Z)");
        }
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
