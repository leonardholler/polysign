package com.polysign.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detects a poisoned DynamoDB connection pool by running a cheap DescribeTable
 * probe. Returns DOWN if the client throws (e.g. "Connection pool shut down"),
 * which causes the Docker healthcheck to flip the container to "unhealthy" and
 * triggers autoheal to restart it.
 *
 * Boot grace: for the first 120s after construction, probe failures return
 * UNKNOWN instead of DOWN to avoid spurious restarts during slow startup.
 * Caching: successful results are cached for 10s to reduce DynamoDB load.
 * Timeout: each DescribeTable call is capped at 5s to keep the health endpoint
 * responsive even when DynamoDB is slow.
 */
@Component
public class DynamoDbHealthIndicator implements HealthIndicator {

    private static final long BOOT_GRACE_MS = 120_000;
    private static final long CACHE_TTL_MS = 10_000;
    private static final Duration DESCRIBE_TIMEOUT = Duration.ofSeconds(5);

    private final DynamoDbClient dynamoDbClient;
    private final String marketsTable;
    private final long startTimeMillis;

    private final AtomicReference<Health> cachedHealth = new AtomicReference<>();
    private volatile long lastSuccessAt = 0;

    @Autowired
    public DynamoDbHealthIndicator(
            DynamoDbClient dynamoDbClient,
            @Value("${polysign.dynamodb.tables.markets:markets}") String marketsTable) {
        this(dynamoDbClient, marketsTable, System.currentTimeMillis());
    }

    /** Package-private — for testing with a synthetic construction time. */
    DynamoDbHealthIndicator(DynamoDbClient dynamoDbClient, String marketsTable, long startTimeMillis) {
        this.dynamoDbClient = dynamoDbClient;
        this.marketsTable = marketsTable;
        this.startTimeMillis = startTimeMillis;
    }

    @Override
    public Health health() {
        long now = System.currentTimeMillis();

        // Return cached UP result if within TTL
        Health cached = cachedHealth.get();
        if (cached != null && (now - lastSuccessAt) < CACHE_TTL_MS) {
            return cached;
        }

        try {
            dynamoDbClient.describeTable(
                    DescribeTableRequest.builder()
                            .tableName(marketsTable)
                            .overrideConfiguration(AwsRequestOverrideConfiguration.builder()
                                    .apiCallTimeout(DESCRIBE_TIMEOUT)
                                    .build())
                            .build());
            Health up = Health.up().build();
            cachedHealth.set(up);
            lastSuccessAt = now;
            return up;
        } catch (Exception e) {
            cachedHealth.set(null);
            if ((now - startTimeMillis) < BOOT_GRACE_MS) {
                return Health.unknown().withDetail("grace", "still booting").withException(e).build();
            }
            return Health.down().withException(e).build();
        }
    }
}
