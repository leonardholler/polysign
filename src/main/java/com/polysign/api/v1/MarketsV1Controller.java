package com.polysign.api.v1;

import com.polysign.api.ApiKeyContext;
import com.polysign.api.v1.dto.MarketV1Dto;
import com.polysign.api.v1.dto.PaginatedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.polysign.common.AppClock;
import com.polysign.model.Market;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GET /api/v1/markets?category=&minVolume=&limit=&cursor=
 *
 * <p>Cursor-paginated market listing. {@code category} filters by exact category match using a
 * DynamoDB filter expression on the scan. {@code minVolume} is applied in-memory because
 * {@code volume24h} is stored as a String — a known DynamoDB modelling trade-off documented
 * in the Market model Javadoc.
 *
 * <p>Limit: default 50, max 200.
 */
@Tag(name = "Markets", description = "Active Polymarket prediction markets")
@RestController
@RequestMapping("/api/v1/markets")
public class MarketsV1Controller {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT     = 200;

    private final DynamoDbTable<Market> marketsTable;
    private final AppClock              clock;

    public MarketsV1Controller(DynamoDbTable<Market> marketsTable, AppClock clock) {
        this.marketsTable = marketsTable;
        this.clock        = clock;
    }

    @Operation(summary = "List markets",
               description = "Cursor-paginated active markets with optional category and volume filters.")
    @GetMapping
    public PaginatedResponse<MarketV1Dto> listMarkets(
            HttpServletRequest request,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double minVolume,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String cursor) {

        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT);
        }

        Map<String, AttributeValue> startKey = decodeCursor(cursor);

        Expression filter = buildFilter(category);
        ScanEnhancedRequest.Builder scanBuilder = ScanEnhancedRequest.builder().limit(limit);
        if (filter != null) scanBuilder.filterExpression(filter);
        if (startKey != null) scanBuilder.exclusiveStartKey(startKey);

        Page<Market> page = marketsTable.scan(scanBuilder.build()).iterator().next();
        List<Market> items = page.items();
        Map<String, AttributeValue> nextKey = page.lastEvaluatedKey();
        boolean hasMore = nextKey != null && !nextKey.isEmpty();
        String nextCursor = hasMore ? CursorCodec.encode(nextKey) : null;

        // In-memory volume filter (volume24h is a String field)
        List<MarketV1Dto> data = items.stream()
                .filter(m -> minVolume == null || meetsMinVolume(m, minVolume))
                .map(this::toDto)
                .collect(Collectors.toList());

        String clientName = ApiKeyContext.getApiKey(request)
                .map(k -> k.getClientName()).orElse(null);

        return PaginatedResponse.of(data, nextCursor, hasMore, clock.now().toString(), clientName);
    }

    private static Expression buildFilter(String category) {
        if (category == null || category.isBlank()) return null;
        return Expression.builder()
                .expression("category = :category")
                .expressionValues(Map.of(":category", AttributeValue.fromS(category)))
                .build();
    }

    private static boolean meetsMinVolume(Market market, double minVolume) {
        if (market.getVolume24h() == null) return false;
        try {
            return new BigDecimal(market.getVolume24h()).doubleValue() >= minVolume;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Map<String, AttributeValue> decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        return CursorCodec.decode(cursor);
    }

    private MarketV1Dto toDto(Market m) {
        return new MarketV1Dto(m.getMarketId(), m.getQuestion(), m.getCategory(),
                m.getEndDate(), m.getVolume24h(), m.getCurrentYesPrice(), m.getSlug());
    }
}
