package com.polysign.processing;

import com.polysign.detector.NewsCorrelationDetector;
import com.polysign.model.Article;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.sqs.SqsClient;

import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class NewsConsumerTest {

    @Test
    void whenDisabledPollDoesNothing() {
        SqsClient sqsClient = mock(SqsClient.class);
        NewsCorrelationDetector detector = mock(NewsCorrelationDetector.class);

        NewsConsumer consumer = new NewsConsumer(
                sqsClient,
                mock(DynamoDbTable.class),
                detector,
                "news-to-process",
                false);

        consumer.poll();

        verifyNoInteractions(sqsClient, detector);
    }
}
