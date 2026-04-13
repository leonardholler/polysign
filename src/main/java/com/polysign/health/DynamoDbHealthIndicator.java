package com.polysign.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;

/**
 * Detects a poisoned DynamoDB connection pool by running a cheap DescribeTable
 * probe. Returns DOWN if the client throws (e.g. "Connection pool shut down"),
 * which causes the Docker healthcheck to flip the container to "unhealthy" and
 * triggers autoheal to restart it.
 */
@Component
public class DynamoDbHealthIndicator implements HealthIndicator {

    private final DynamoDbClient dynamoDbClient;
    private final String marketsTable;

    public DynamoDbHealthIndicator(
            DynamoDbClient dynamoDbClient,
            @Value("${polysign.dynamodb.tables.markets:markets}") String marketsTable) {
        this.dynamoDbClient = dynamoDbClient;
        this.marketsTable = marketsTable;
    }

    @Override
    public Health health() {
        try {
            dynamoDbClient.describeTable(
                    DescribeTableRequest.builder().tableName(marketsTable).build());
            return Health.up().build();
        } catch (Exception e) {
            return Health.down().withException(e).build();
        }
    }
}
