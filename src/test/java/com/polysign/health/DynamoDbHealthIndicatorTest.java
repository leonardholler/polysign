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

        Health health = new DynamoDbHealthIndicator(client, "markets").health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    void returnsDownWhenSdkClientExceptionThrown() {
        DynamoDbClient client = mock(DynamoDbClient.class);
        when(client.describeTable(any(DescribeTableRequest.class)))
                .thenThrow(SdkClientException.create("connection refused"));

        Health health = new DynamoDbHealthIndicator(client, "markets").health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}
