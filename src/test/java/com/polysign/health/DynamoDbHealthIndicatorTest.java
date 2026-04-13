package com.polysign.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamoDbHealthIndicatorTest {

    @Test
    void returnsUpWhenDescribeTableSucceeds() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(DescribeTableResponse.builder().build());

        Health health = new DynamoDbHealthIndicator(client, "markets").health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void returnsDownWhenConnectionPoolShutDown() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenThrow(new IllegalStateException("Connection pool shut down"));

        long pastStart = System.currentTimeMillis() - 200_000;
        Health health = new DynamoDbHealthIndicator(client, "markets", pastStart).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    void returnsDownWhenSdkClientExceptionThrown() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenThrow(SdkClientException.create("connection refused"));

        long pastStart = System.currentTimeMillis() - 200_000;
        Health health = new DynamoDbHealthIndicator(client, "markets", pastStart).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    void returnsUnknownDuringBootGraceWindowWhenClientThrows() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenThrow(new IllegalStateException("DynamoDB not ready"));

        // startTime is now — we are within the 120s grace window
        Health health = new DynamoDbHealthIndicator(client, "markets", System.currentTimeMillis()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void returnsDownAfterGraceWindowExpires() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenThrow(new IllegalStateException("DynamoDB not ready"));

        // startTime 200s ago — well past the 120s grace window
        long expiredStart = System.currentTimeMillis() - 200_000;
        Health health = new DynamoDbHealthIndicator(client, "markets", expiredStart).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void cachesSuccessfulResultAndCallsDescribeTableOnlyOnce() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(DescribeTableResponse.builder().build());

        DynamoDbHealthIndicator indicator = new DynamoDbHealthIndicator(client, "markets");
        indicator.health(); // first call — hits DynamoDB
        indicator.health(); // second call — should return cached result

        verify(client, times(1)).describeTable(any(DescribeTableRequest.class));
    }
}
