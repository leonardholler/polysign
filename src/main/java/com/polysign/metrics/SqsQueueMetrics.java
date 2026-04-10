package com.polysign.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers Micrometer gauges for SQS main queue and DLQ depths.
 *
 * <p>Gauges are backed by an in-memory {@link ConcurrentHashMap} that is refreshed
 * every 60 seconds via {@link #refresh()}. Using a backing map rather than a direct
 * SQS call per gauge avoids a metric-scrape latency spike and batches all 6 SQS calls
 * into a single scheduled task.
 *
 * <p>Metrics emitted:
 * <ul>
 *   <li>{@code polysign.sqs.queue.depth} (gauge, tag: queue) — 3 main queues</li>
 *   <li>{@code polysign.dlq.depth} (gauge, tag: queue) — 3 DLQs</li>
 * </ul>
 */
@Component
public class SqsQueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(SqsQueueMetrics.class);

    private final SqsClient sqsClient;

    // Queue names (resolved from config at startup)
    private final String newsQueue;
    private final String walletQueue;
    private final String alertsQueue;
    private final String newsDlq;
    private final String walletDlq;
    private final String alertsDlq;

    /** Backing map — key is queue name, value is approximate message count. */
    private final ConcurrentHashMap<String, Long> depthCache = new ConcurrentHashMap<>();

    public SqsQueueMetrics(
            SqsClient sqsClient,
            MeterRegistry meterRegistry,
            @Value("${polysign.sqs.queues.news-to-process:news-to-process}")                   String newsQueue,
            @Value("${polysign.sqs.queues.wallet-trades-to-process:wallet-trades-to-process}") String walletQueue,
            @Value("${polysign.sqs.queues.alerts-to-notify:alerts-to-notify}")                 String alertsQueue,
            @Value("${polysign.sqs.dlq.news-to-process:news-to-process-dlq}")                  String newsDlq,
            @Value("${polysign.sqs.dlq.wallet-trades-to-process:wallet-trades-to-process-dlq}") String walletDlq,
            @Value("${polysign.sqs.dlq.alerts-to-notify:alerts-to-notify-dlq}")               String alertsDlq) {

        this.sqsClient   = sqsClient;
        this.newsQueue   = newsQueue;
        this.walletQueue = walletQueue;
        this.alertsQueue = alertsQueue;
        this.newsDlq     = newsDlq;
        this.walletDlq   = walletDlq;
        this.alertsDlq   = alertsDlq;

        // Main queues
        for (String q : new String[]{newsQueue, walletQueue, alertsQueue}) {
            final String name = q;
            Gauge.builder("polysign.sqs.queue.depth", depthCache,
                            m -> m.getOrDefault(name, 0L).doubleValue())
                    .tag("queue", name)
                    .description("Approximate number of messages in the SQS main queue")
                    .register(meterRegistry);
        }

        // DLQs
        for (String q : new String[]{newsDlq, walletDlq, alertsDlq}) {
            final String name = q;
            Gauge.builder("polysign.dlq.depth", depthCache,
                            m -> m.getOrDefault(name, 0L).doubleValue())
                    .tag("queue", name)
                    .description("Approximate number of messages in the DLQ")
                    .register(meterRegistry);
        }
    }

    /**
     * Refreshes the depth cache for all 6 queues every 60 seconds.
     * A failure for one queue is logged at WARN and does not block the others.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 35_000)
    public void refresh() {
        for (String queueName : new String[]{
                newsQueue, walletQueue, alertsQueue,
                newsDlq, walletDlq, alertsDlq}) {
            try {
                String url = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
                String raw = sqsClient.getQueueAttributes(r -> r
                        .queueUrl(url)
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                ).attributes().getOrDefault(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0");
                depthCache.put(queueName, Long.parseLong(raw));
            } catch (Exception e) {
                log.warn("sqs_depth_refresh_failed queue={} error={}", queueName, e.getMessage());
            }
        }
        log.debug("sqs_depth_refreshed queues={}", Map.copyOf(depthCache));
    }
}
