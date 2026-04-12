package com.polysign.processing;

import com.polysign.common.CorrelationId;
import com.polysign.detector.NewsCorrelationDetector;
import com.polysign.model.Article;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * SQS consumer that drains the {@code news-to-process} queue and runs
 * correlation detection against all active markets for each new article.
 *
 * <p>Polling model: long-poll (20 s server-side wait) — mirrors
 * {@link com.polysign.notification.NotificationConsumer}.
 *
 * <p>Delivery guarantee: the SQS message is deleted only after
 * {@link NewsCorrelationDetector#checkMarkets} returns without throwing.
 * A failure leaves the message visible again after the queue's visibility
 * timeout, allowing up to {@code maxReceiveCount} (5) retries before it
 * moves to the DLQ.
 */
@Component
public class NewsConsumer {

    private static final Logger log = LoggerFactory.getLogger(NewsConsumer.class);
    private static final int SQS_LONG_POLL_SECONDS = 20;
    private static final int SQS_MAX_MESSAGES      = 10;

    private final boolean                 enabled;
    private final SqsClient               sqsClient;
    private final DynamoDbTable<Article>  articlesTable;
    private final NewsCorrelationDetector newsCorrelationDetector;
    private final String                  newsQueueName;

    // Lazily resolved — BootstrapRunner creates the queue after beans are wired.
    volatile String newsQueueUrl;

    public NewsConsumer(
            SqsClient sqsClient,
            DynamoDbTable<Article>  articlesTable,
            NewsCorrelationDetector newsCorrelationDetector,
            @Value("${polysign.sqs.queues.news-to-process:news-to-process}") String newsQueueName,
            @Value("${polysign.detectors.news.enabled:false}") boolean enabled) {
        this.enabled                 = enabled;
        this.sqsClient               = sqsClient;
        this.articlesTable           = articlesTable;
        this.newsCorrelationDetector = newsCorrelationDetector;
        this.newsQueueName           = newsQueueName;
    }

    /**
     * Long-polls the {@code news-to-process} queue and processes each message.
     * {@code fixedDelay} ensures at most one concurrent poll cycle.
     */
    @Scheduled(fixedDelayString = "${polysign.consumer.news.poll-interval-ms:1000}")
    public void poll() {
        if (!enabled) {
            log.debug("event=news_consumer_disabled");
            return;
        }
        var messages = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                        .queueUrl(resolveQueueUrl())
                        .maxNumberOfMessages(SQS_MAX_MESSAGES)
                        .waitTimeSeconds(SQS_LONG_POLL_SECONDS)
                        .build()).messages();

        for (Message msg : messages) {
            process(msg);
        }
    }

    private void process(Message msg) {
        String articleId = msg.body();
        try (var ignored = CorrelationId.set(
                articleId.substring(0, Math.min(12, articleId.length())))) {
            Article article = articlesTable.getItem(
                    Key.builder().partitionValue(articleId).build());
            if (article == null) {
                // Stale message — article was never written or already expired.
                log.warn("event=news_article_not_found articleId={}", articleId);
                deleteMessage(msg);
                return;
            }

            newsCorrelationDetector.checkMarkets(article);
            deleteMessage(msg);
            log.debug("event=news_article_processed articleId={}", articleId);
        } catch (Exception e) {
            // Leave message in queue — visibility timeout will requeue it.
            log.warn("event=news_process_error articleId={} error={}", articleId, e.getMessage());
        }
    }

    private void deleteMessage(Message msg) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(resolveQueueUrl())
                .receiptHandle(msg.receiptHandle())
                .build());
    }

    private String resolveQueueUrl() {
        String url = newsQueueUrl;
        if (url == null) {
            url = sqsClient.getQueueUrl(r -> r.queueName(newsQueueName)).queueUrl();
            newsQueueUrl = url;
            log.info("NewsConsumer resolved queue URL: {}", url);
        }
        return url;
    }
}
