package com.polysign.detector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Captures orderbook depth from the Polymarket CLOB at alert-fire time.
 *
 * <p>Called by detectors only when they decide to fire an alert — not on every
 * poll cycle. The 500 ms timeout budget ensures book capture never delays
 * alert delivery. On any failure the detector fires the alert with null
 * book fields.
 */
@Component
public class OrderbookService {

    private static final Logger log = LoggerFactory.getLogger(OrderbookService.class);
    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final String CLOB_CB_NAME = "polymarket-clob";

    private final WebClient clobClient;
    private final CircuitBreaker circuitBreaker;
    private final RateLimiter rateLimiter;
    private final ObjectMapper mapper;

    public OrderbookService(
            @Qualifier("clobApiClient") WebClient clobClient,
            CircuitBreakerRegistry cbRegistry,
            RateLimiterRegistry rateLimiterRegistry,
            ObjectMapper mapper) {
        this.clobClient = clobClient;
        this.circuitBreaker = cbRegistry.circuitBreaker(CLOB_CB_NAME);
        this.rateLimiter = rateLimiterRegistry.rateLimiter(CLOB_CB_NAME);
        this.mapper = mapper;
    }

    /**
     * Fetch the orderbook for the given token and compute spread and depth.
     *
     * @param yesTokenId the CLOB token ID for the YES outcome
     * @return book snapshot, or empty if the call fails or times out
     */
    public Optional<BookSnapshot> capture(String yesTokenId) {
        if (yesTokenId == null || yesTokenId.isBlank()) {
            return Optional.empty();
        }
        try {
            Supplier<String> call = () -> clobClient.get()
                    .uri(u -> u.path("/book").queryParam("token_id", yesTokenId).build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);

            // RateLimiter → CircuitBreaker (no retry — 500ms budget is one shot)
            String body = RateLimiter.decorateSupplier(rateLimiter,
                    CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();

            return parseBook(body);
        } catch (Exception e) {
            log.debug("orderbook_capture_failed tokenId={} error={}", yesTokenId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<BookSnapshot> parseBook(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        List<Level> bids = parseLevels(root.path("bids"));
        List<Level> asks = parseLevels(root.path("asks"));

        if (bids.isEmpty() || asks.isEmpty()) {
            return Optional.empty();
        }

        double bestBid = bids.getFirst().price;
        double bestAsk = asks.getFirst().price;
        double midpoint = (bestBid + bestAsk) / 2.0;

        if (midpoint <= 0) {
            return Optional.empty();
        }

        double spreadBps = computeSpreadBps(bestBid, bestAsk, midpoint);
        double depthAtMid = computeDepthAtMid(bids, asks, midpoint);

        return Optional.of(new BookSnapshot(spreadBps, depthAtMid));
    }

    private List<Level> parseLevels(JsonNode node) {
        List<Level> levels = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode entry : node) {
                double price = Double.parseDouble(entry.path("price").asText());
                double size = Double.parseDouble(entry.path("size").asText());
                levels.add(new Level(price, size));
            }
        }
        return levels;
    }

    // ── Computation methods — package-private for testing ────────────────────

    static double computeSpreadBps(double bestBid, double bestAsk, double midpoint) {
        return (bestAsk - bestBid) / midpoint * 10_000;
    }

    static double computeDepthAtMid(List<Level> bids, List<Level> asks, double midpoint) {
        double lower = midpoint * 0.99;
        double upper = midpoint * 1.01;
        double depth = 0.0;

        for (Level bid : bids) {
            if (bid.price >= lower) {
                depth += bid.size * bid.price;
            }
        }
        for (Level ask : asks) {
            if (ask.price <= upper) {
                depth += ask.size * ask.price;
            }
        }
        return depth;
    }

    /** A single price level in the orderbook. */
    record Level(double price, double size) {}

    /** Spread and depth snapshot captured at alert-fire time. */
    public record BookSnapshot(double spreadBps, double depthAtMid) {}
}
