package com.polysign.api.v1;

import com.polysign.api.ApiKeyContext;
import com.polysign.api.v1.dto.PaginatedResponse;
import com.polysign.api.v1.dto.SnapshotV1Dto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.polysign.common.AppClock;
import com.polysign.model.PriceSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;

/**
 * GET /api/v1/snapshots?marketId=(required)&since=&limit=100&cursor=
 *
 * <p>Cursor-paginated price snapshots for a single market. {@code marketId} is required;
 * missing it returns 400. Uses DynamoDB query (not scan) on the primary key — efficient.
 *
 * <p>Limit: default 100, max 500.
 */
@Tag(name = "Snapshots", description = "Price snapshot history by market")
@RestController
@RequestMapping("/api/v1/snapshots")
public class SnapshotsV1Controller {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT     = 500;

    private final DynamoDbTable<PriceSnapshot> snapshotsTable;
    private final AppClock                     clock;

    public SnapshotsV1Controller(DynamoDbTable<PriceSnapshot> snapshotsTable, AppClock clock) {
        this.snapshotsTable = snapshotsTable;
        this.clock          = clock;
    }

    @Operation(summary = "List price snapshots for a market",
               description = "marketId is required. Cursor-paginated.")
    @GetMapping
    public PaginatedResponse<SnapshotV1Dto> listSnapshots(
            HttpServletRequest request,
            @RequestParam String marketId,
            @RequestParam(required = false) String since,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String cursor) {

        if (marketId == null || marketId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "marketId is required");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT);
        }

        QueryConditional qc = since != null
                ? QueryConditional.sortGreaterThanOrEqualTo(
                        Key.builder().partitionValue(marketId).sortValue(parseIso(since)).build())
                : QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(marketId).build());

        Map<String, AttributeValue> startKey = decodeCursor(cursor);

        QueryEnhancedRequest.Builder queryBuilder = QueryEnhancedRequest.builder()
                .queryConditional(qc)
                .limit(limit);
        if (startKey != null) queryBuilder.exclusiveStartKey(startKey);

        Page<PriceSnapshot> page = snapshotsTable.query(queryBuilder.build()).iterator().next();
        List<PriceSnapshot> items = page.items();
        Map<String, AttributeValue> nextKey = page.lastEvaluatedKey();
        boolean hasMore = nextKey != null && !nextKey.isEmpty();
        String nextCursor = hasMore ? CursorCodec.encode(nextKey) : null;

        String clientName = ApiKeyContext.getApiKey(request)
                .map(k -> k.getClientName()).orElse(null);

        List<SnapshotV1Dto> data = items.stream().map(this::toDto).toList();
        return PaginatedResponse.of(data, nextCursor, hasMore, clock.now().toString(), clientName);
    }

    private static Map<String, AttributeValue> decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        return CursorCodec.decode(cursor);
    }

    private String parseIso(String since) {
        try {
            return java.time.Instant.parse(since).toString();
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid 'since' parameter. Must be ISO-8601 (e.g. 2026-04-01T00:00:00Z)");
        }
    }

    private SnapshotV1Dto toDto(PriceSnapshot s) {
        return new SnapshotV1Dto(s.getMarketId(), s.getTimestamp(),
                s.getYesPrice(), s.getNoPrice(), s.getVolume24h(), s.getMidpoint());
    }
}
