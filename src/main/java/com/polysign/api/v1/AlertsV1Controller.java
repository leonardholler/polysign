package com.polysign.api.v1;

import com.polysign.api.ApiKeyContext;
import com.polysign.api.v1.dto.AlertV1Dto;
import com.polysign.api.v1.dto.PaginatedResponse;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Duration;
import java.util.*;

/**
 * GET /api/v1/alerts
 *
 * <p>Cursor-paginated alert feed. Requires X-API-Key (enforced by {@link com.polysign.api.ApiKeyAuthFilter}).
 *
 * <p>Signal strength (distinct detector types per market in the last 60 minutes) is computed
 * from a full scan of recent alerts on each request — same logic as the internal dashboard.
 */
@Tag(name = "Alerts", description = "Paginated alert feed with signal strength")
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertsV1Controller {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT     = 200;
    private static final int SIGNAL_WINDOW_MIN = 60;

    private final DynamoDbTable<Alert> alertsTable;
    private final AppClock             clock;

    public AlertsV1Controller(DynamoDbTable<Alert> alertsTable, AppClock clock) {
        this.alertsTable = alertsTable;
        this.clock       = clock;
    }

    @Operation(summary = "List alerts",
               description = "Cursor-paginated alert feed. X-API-Key required.")
    @GetMapping
    public PaginatedResponse<AlertV1Dto> listAlerts(
            HttpServletRequest request,
            @RequestParam(required = false) String marketId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String minSeverity,
            @RequestParam(required = false) String since,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String cursor) {

        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT);
        }

        String effectiveSince = since != null
                ? parseIso(since)
                : clock.now().minus(Duration.ofDays(7)).toString();

        String window60m = clock.now().minus(Duration.ofMinutes(SIGNAL_WINDOW_MIN)).toString();

        // Signal strength: distinct detector types per market in last 60 min
        Map<String, Integer> signalStrengths = computeSignalStrengths(window60m);

        // Decode pagination cursor
        Map<String, AttributeValue> startKey = decodeCursor(cursor);

        // Build filter and paginated scan
        Expression filter = buildFilter(marketId, type, minSeverity, effectiveSince);
        ScanEnhancedRequest.Builder scanBuilder = ScanEnhancedRequest.builder().limit(limit);
        if (filter != null) scanBuilder.filterExpression(filter);
        if (startKey != null) scanBuilder.exclusiveStartKey(startKey);

        Page<Alert> page = alertsTable.scan(scanBuilder.build()).iterator().next();
        List<Alert> items = page.items();
        Map<String, AttributeValue> nextKey = page.lastEvaluatedKey();
        boolean hasMore = nextKey != null && !nextKey.isEmpty();
        String nextCursor = hasMore ? CursorCodec.encode(nextKey) : null;

        String clientName = ApiKeyContext.getApiKey(request)
                .map(k -> k.getClientName()).orElse(null);

        List<AlertV1Dto> data = items.stream()
                .map(a -> toDto(a, signalStrengths.getOrDefault(a.getMarketId(), 1)))
                .toList();

        return PaginatedResponse.of(data, nextCursor, hasMore, clock.now().toString(), clientName);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Integer> computeSignalStrengths(String window60m) {
        Map<String, Set<String>> byMarket = new HashMap<>();
        alertsTable.scan().items().forEach(a -> {
            if (a.getCreatedAt() == null || a.getMarketId() == null || a.getType() == null) return;
            if (a.getCreatedAt().compareTo(window60m) >= 0) {
                byMarket.computeIfAbsent(a.getMarketId(), k -> new HashSet<>()).add(a.getType());
            }
        });
        Map<String, Integer> result = new HashMap<>();
        byMarket.forEach((market, types) -> result.put(market, types.size()));
        return result;
    }

    private Expression buildFilter(String marketId, String type,
                                   String minSeverity, String since) {
        List<String> conditions = new ArrayList<>();
        Map<String, AttributeValue> values = new HashMap<>();
        Map<String, String> names = new HashMap<>();

        if (marketId != null && !marketId.isBlank()) {
            conditions.add("marketId = :marketId");
            values.put(":marketId", AttributeValue.fromS(marketId));
        }
        if (type != null && !type.isBlank()) {
            // 'type' is a DynamoDB reserved word — alias required
            conditions.add("#alertType = :alertType");
            values.put(":alertType", AttributeValue.fromS(type));
            names.put("#alertType", "type");
        }
        if (minSeverity != null && !minSeverity.isBlank()) {
            List<String> allowed = allowedSeverities(minSeverity);
            switch (allowed.size()) {
                case 1 -> {
                    conditions.add("severity = :sev0");
                    values.put(":sev0", AttributeValue.fromS(allowed.get(0)));
                }
                case 2 -> {
                    conditions.add("severity IN (:sev0, :sev1)");
                    values.put(":sev0", AttributeValue.fromS(allowed.get(0)));
                    values.put(":sev1", AttributeValue.fromS(allowed.get(1)));
                }
                // size 3 → include all severities, no filter needed
            }
        }
        if (since != null && !since.isBlank()) {
            conditions.add("createdAt >= :since");
            values.put(":since", AttributeValue.fromS(since));
        }

        if (conditions.isEmpty()) return null;

        Expression.Builder builder = Expression.builder()
                .expression(String.join(" AND ", conditions))
                .expressionValues(values);
        if (!names.isEmpty()) builder.expressionNames(names);
        return builder.build();
    }

    /** Returns the set of allowed severity strings for a given minSeverity level. */
    private static List<String> allowedSeverities(String minSeverity) {
        return switch (minSeverity.toLowerCase()) {
            case "critical" -> List.of("critical");
            case "warning"  -> List.of("warning", "critical");
            default         -> List.of("info", "warning", "critical"); // info or unknown → all
        };
    }

    private static Map<String, AttributeValue> decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        return CursorCodec.decode(cursor); // throws InvalidCursorException → 400
    }

    private String parseIso(String since) {
        try {
            return java.time.Instant.parse(since).toString();
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid 'since' parameter. Must be ISO-8601 (e.g. 2026-04-01T00:00:00Z)");
        }
    }

    private static AlertV1Dto toDto(Alert a, int signalStrength) {
        return new AlertV1Dto(
                a.getAlertId(), a.getCreatedAt(), a.getType(), a.getSeverity(),
                a.getMarketId(), a.getTitle(), a.getDescription(), a.getMetadata(),
                a.getPhoneWorthy(), a.getLink(), signalStrength);
    }
}
