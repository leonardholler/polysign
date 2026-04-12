package com.polysign.poller;

import com.polysign.common.AppClock;
import com.polysign.config.RssProperties;
import com.polysign.processing.KeywordExtractor;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.List;

import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class RssPollerTest {

    @Test
    void whenDisabledPollFeedsDoesNothing() {
        SqsClient sqsClient = mock(SqsClient.class);
        S3Client  s3Client  = mock(S3Client.class);

        RssPoller poller = new RssPoller(
                new RssProperties(List.of("https://example.com/feed.rss")),
                mock(DynamoDbTable.class),
                s3Client,
                sqsClient,
                mock(KeywordExtractor.class),
                new AppClock(),
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(),
                "polysign-archives",
                "news-to-process",
                false);

        poller.pollFeeds();

        verifyNoInteractions(sqsClient, s3Client);
    }
}
