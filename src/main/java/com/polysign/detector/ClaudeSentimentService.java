package com.polysign.detector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls the Anthropic Messages API to score a news article's directional sentiment
 * relative to a specific prediction market question.
 *
 * <p>Keyword matching cannot determine directionality — "Trump expected to lose" and
 * "Trump expected to win" score identically. This service replaces keyword-based
 * relevance scoring with directional sentiment: a float from -1.0 (strongly suggests
 * NO) to +1.0 (strongly suggests YES).
 *
 * <p>Falls back gracefully to null when:
 * <ul>
 *   <li>{@code ANTHROPIC_API_KEY} is not configured</li>
 *   <li>The circuit breaker is open (API repeatedly failing)</li>
 *   <li>The call times out</li>
 * </ul>
 * Callers must fall back to keyword-only scoring in all null cases.
 *
 * <p>Resilience: wrapped in the {@code claude-api} Resilience4j circuit breaker.
 * Rate: at most as fast as {@code NewsConsumer} dequeues — well under 10/min.
 */
@Component
public class ClaudeSentimentService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSentimentService.class);
    private static final String CB_NAME = "claude-api";

    private final WebClient      claudeClient;
    private final ObjectMapper   mapper;
    private final CircuitBreaker circuitBreaker;
    private final String         model;
    private final int            maxTokens;
    private final Duration       timeout;
    private final String         apiKey;
    private final Counter        callsSuccess;
    private final Counter        callsFallback;
    private final Counter        callsError;

    /** Result of a Claude sentiment analysis call. */
    public record SentimentResult(
            boolean relevant,
            double  sentiment,      // -1.0 (NO) to +1.0 (YES)
            double  confidence,     // 0.0–1.0
            String  reasoning       // one-sentence explanation
    ) {}

    public ClaudeSentimentService(
            @Qualifier("claudeApiClient") WebClient claudeClient,
            ObjectMapper mapper,
            CircuitBreakerRegistry cbRegistry,
            MeterRegistry meterRegistry,
            @Value("${polysign.detectors.news.claude-model:claude-sonnet-4-6}")     String model,
            @Value("${polysign.detectors.news.claude-max-tokens:150}")              int    maxTokens,
            @Value("${polysign.detectors.news.claude-timeout-ms:5000}")             long   timeoutMs,
            @Value("${ANTHROPIC_API_KEY:}")                                          String apiKey) {
        this.claudeClient   = claudeClient;
        this.mapper         = mapper;
        this.circuitBreaker = cbRegistry.circuitBreaker(CB_NAME);
        this.model          = model;
        this.maxTokens      = maxTokens;
        this.timeout        = Duration.ofMillis(timeoutMs);
        this.apiKey         = apiKey;
        this.callsSuccess   = counter(meterRegistry, "success");
        this.callsFallback  = counter(meterRegistry, "fallback");
        this.callsError     = counter(meterRegistry, "error");
    }

    /**
     * Analyze a news article's directional sentiment relative to the given market.
     *
     * @return {@link SentimentResult} on success, or {@code null} when Claude is
     *         unavailable — callers MUST fall back to keyword-only scoring.
     */
    public SentimentResult analyze(String marketQuestion, double currentPrice,
                                   String articleTitle, String articleSummary) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("claude_sentiment_skipped reason=no_api_key");
            callsFallback.increment();
            return null;
        }
        try {
            SentimentResult result = circuitBreaker.executeCallable(
                    () -> callClaude(marketQuestion, currentPrice, articleTitle, articleSummary));
            callsSuccess.increment();
            log.debug("claude_sentiment_ok market={} sentiment={} confidence={}",
                    truncate(marketQuestion, 60), result.sentiment(), result.confidence());
            return result;
        } catch (Exception e) {
            log.warn("claude_sentiment_unavailable error={} — falling back to keyword scoring",
                    e.getMessage());
            callsError.increment();
            return null;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private SentimentResult callClaude(String marketQuestion, double currentPrice,
                                       String articleTitle, String articleSummary) throws Exception {
        String prompt = buildPrompt(marketQuestion, currentPrice, articleTitle, articleSummary);

        String requestJson = mapper.writeValueAsString(Map.of(
                "model",      model,
                "max_tokens", maxTokens,
                "temperature", 0,
                "messages",   List.of(Map.of("role", "user", "content", prompt))
        ));

        String responseBody = claudeClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .bodyValue(requestJson)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .block();

        return parseResponse(responseBody);
    }

    private String buildPrompt(String marketQuestion, double currentPrice,
                               String articleTitle, String articleSummary) {
        return """
                You are evaluating a news article's impact on a prediction market.

                Market question: "%s"
                Market current price: %.1f\u00a2 (probability of YES)
                Article title: "%s"
                Article summary: "%s"

                Respond ONLY with a JSON object, no other text:
                {
                  "relevant": true/false,
                  "sentiment": <float between -1.0 (strongly suggests NO) and +1.0 (strongly suggests YES)>,
                  "confidence": <float between 0.0 and 1.0>,
                  "reasoning": "<one sentence explanation>"
                }

                If the article is not relevant to this specific market, set relevant=false and sentiment=0.
                """.formatted(marketQuestion, currentPrice * 100.0,
                articleTitle, articleSummary != null ? articleSummary : "");
    }

    private SentimentResult parseResponse(String responseBody) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        String text  = root.path("content").path(0).path("text").asText();
        // Strip markdown code fences if the model wraps its response
        text = text.replaceAll("(?s)```json\\s*|```\\s*", "").trim();
        JsonNode parsed = mapper.readTree(text);
        return new SentimentResult(
                parsed.path("relevant").asBoolean(false),
                parsed.path("sentiment").asDouble(0.0),
                parsed.path("confidence").asDouble(0.0),
                parsed.path("reasoning").asText("")
        );
    }

    private static Counter counter(MeterRegistry registry, String status) {
        return Counter.builder("polysign.news.sentiment.calls")
                .tag("status", status)
                .register(registry);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
