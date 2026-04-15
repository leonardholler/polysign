package com.polysign.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;
import java.util.Map;

/**
 * Idempotent infrastructure bootstrap — runs once at application startup (order=1).
 *
 * <p>Creates the 7 DynamoDB tables, 2 SQS DLQs + 2 main queues, and the S3 bucket
 * if they do not already exist. Re-running (or restarting the app) is safe.
 *
 * <p>Uses the low-level {@link DynamoDbClient} for table creation so that GSIs,
 * billing mode (PAY_PER_REQUEST), and TTL can all be specified precisely.
 * The {@link DynamoConfig} still provides typed {@code DynamoDbTable<T>} beans via
 * the Enhanced Client for all data-access paths.
 */
@Component
@Order(1)
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);
    private static final int DLQ_MAX_RECEIVE_COUNT = 5;

    private final DynamoDbClient dynamoDbClient;
    private final SqsClient      sqsClient;
    private final S3Client        s3Client;

    @Value("${aws.region:us-east-1}")
    private String region;

    @Value("${polysign.s3.archives-bucket:polysign-archives}")
    private String archivesBucket;

    public BootstrapRunner(DynamoDbClient dynamoDbClient, SqsClient sqsClient, S3Client s3Client) {
        this.dynamoDbClient = dynamoDbClient;
        this.sqsClient      = sqsClient;
        this.s3Client       = s3Client;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Bootstrap starting — creating DynamoDB tables, SQS queues, and S3 bucket");
        bootstrapDynamoDb();
        bootstrapSqs();
        bootstrapS3();
        log.info("Bootstrap complete");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DynamoDB
    // ══════════════════════════════════════════════════════════════════════════

    private void bootstrapDynamoDb() {

        // ── markets ──────────────────────────────────────────────────────────
        createTable(
            "markets",
            List.of(
                attr("marketId",    ScalarAttributeType.S),
                attr("category",    ScalarAttributeType.S),
                attr("updatedAt",   ScalarAttributeType.S),
                attr("conditionId", ScalarAttributeType.S)
            ),
            List.of(key("marketId", KeyType.HASH)),
            List.of(
                gsi("category-updatedAt-index",
                    key("category",  KeyType.HASH),
                    key("updatedAt", KeyType.RANGE)),
                // KEYS_ONLY projection: WalletPoller only needs marketId from
                // the conditionId lookup, and marketId is automatically included
                // as the base table PK. Full projection would double storage
                // cost for zero benefit. Inlined rather than using gsi() helper
                // because the helper uses ProjectionType.ALL, which is correct
                // for the other three GSIs but wrong for this one.
                GlobalSecondaryIndex.builder()
                    .indexName("conditionId-index")
                    .keySchema(key("conditionId", KeyType.HASH))
                    .projection(Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
                    .build()
            )
        );

        // ── price_snapshots ───────────────────────────────────────────────────
        createTable(
            "price_snapshots",
            List.of(
                attr("marketId",  ScalarAttributeType.S),
                attr("timestamp", ScalarAttributeType.S)
            ),
            List.of(
                key("marketId",  KeyType.HASH),
                key("timestamp", KeyType.RANGE)
            ),
            List.of()
        );
        enableTtl("price_snapshots", "expiresAt");

        // ── watched_wallets ───────────────────────────────────────────────────
        createTable(
            "watched_wallets",
            List.of(attr("address", ScalarAttributeType.S)),
            List.of(key("address",  KeyType.HASH)),
            List.of()
        );

        // ── wallet_trades ─────────────────────────────────────────────────────
        // SK is txHash (not timestamp) — natural idempotency key; re-processing
        // the same on-chain trade from the Data API is a no-op (PutItem overwrite
        // on the same PK+SK writes identical data). timestamp is a non-key attribute
        // that serves as the GSI SK for time-range queries.
        createTable(
            "wallet_trades",
            List.of(
                attr("address",   ScalarAttributeType.S),
                attr("txHash",    ScalarAttributeType.S),
                attr("marketId",  ScalarAttributeType.S),
                attr("timestamp", ScalarAttributeType.S)
            ),
            List.of(
                key("address", KeyType.HASH),
                key("txHash",  KeyType.RANGE)
            ),
            List.of(
                gsi("marketId-timestamp-index",
                    key("marketId",  KeyType.HASH),
                    key("timestamp", KeyType.RANGE))
            )
        );

        // ── alerts ────────────────────────────────────────────────────────────
        // nextEvaluationDue-index: PK=evaluationStatus ("PENDING"), SK=nextEvaluationDue.
        // AlertOutcomeEvaluator queries this GSI instead of full-scanning the table,
        // keeping memory usage O(batch_size) instead of O(total_alerts).
        createTable(
            "alerts",
            List.of(
                attr("alertId",          ScalarAttributeType.S),
                attr("createdAt",        ScalarAttributeType.S),
                attr("marketId",         ScalarAttributeType.S),
                attr("evaluationStatus", ScalarAttributeType.S),
                attr("nextEvaluationDue", ScalarAttributeType.S)
            ),
            List.of(
                key("alertId",   KeyType.HASH),
                key("createdAt", KeyType.RANGE)
            ),
            List.of(
                gsi("marketId-createdAt-index",
                    key("marketId",  KeyType.HASH),
                    key("createdAt", KeyType.RANGE)),
                gsi("nextEvaluationDue-index",
                    key("evaluationStatus",  KeyType.HASH),
                    key("nextEvaluationDue", KeyType.RANGE))
            )
        );
        enableTtl("alerts", "expiresAt");

        // ── api_keys ──────────────────────────────────────────────────────────
        // PK: apiKeyHash (S) — SHA-256 hex digest. Raw key is never stored.
        createTable(
            "api_keys",
            List.of(attr("apiKeyHash", ScalarAttributeType.S)),
            List.of(key("apiKeyHash", KeyType.HASH)),
            List.of()
        );

        // ── wallet_metadata ───────────────────────────────────────────────────
        createTable(
            "wallet_metadata",
            List.of(attr("address", ScalarAttributeType.S)),
            List.of(key("address", KeyType.HASH)),
            List.of()
        );
        enableTtl("wallet_metadata", "expiresAt");

        // ── alert_outcomes ────────────────────────────────────────────────────
        // No TTL — outcomes are cheap and the whole point is long-term measurement.
        // GSI type-firedAt-index enables per-detector aggregation queries.
        createTable(
            "alert_outcomes",
            List.of(
                attr("alertId", ScalarAttributeType.S),
                attr("horizon", ScalarAttributeType.S),
                attr("type",    ScalarAttributeType.S),
                attr("firedAt", ScalarAttributeType.S)
            ),
            List.of(
                key("alertId", KeyType.HASH),
                key("horizon", KeyType.RANGE)
            ),
            List.of(
                gsi("type-firedAt-index",
                    key("type",    KeyType.HASH),
                    key("firedAt", KeyType.RANGE))
            )
        );
    }

    private void createTable(String tableName,
                             List<AttributeDefinition> attributes,
                             List<KeySchemaElement> keySchema,
                             List<GlobalSecondaryIndex> gsis) {
        try {
            var requestBuilder = CreateTableRequest.builder()
                    .tableName(tableName)
                    .attributeDefinitions(attributes)
                    .keySchema(keySchema)
                    .billingMode(BillingMode.PAY_PER_REQUEST);

            if (!gsis.isEmpty()) {
                requestBuilder.globalSecondaryIndexes(gsis);
            }

            dynamoDbClient.createTable(requestBuilder.build());
            log.info("Created DynamoDB table: {}", tableName);

        } catch (ResourceInUseException e) {
            log.debug("DynamoDB table already exists (idempotent): {}", tableName);
        } catch (Exception e) {
            log.error("Failed to create DynamoDB table={}: {}", tableName, e.getMessage(), e);
            throw e;
        }
    }

    private void enableTtl(String tableName, String ttlAttribute) {
        try {
            dynamoDbClient.updateTimeToLive(r -> r
                    .tableName(tableName)
                    .timeToLiveSpecification(s -> s
                            .enabled(true)
                            .attributeName(ttlAttribute)));
            log.info("Enabled TTL on table={} attribute={}", tableName, ttlAttribute);
        } catch (Exception e) {
            // TTL may already be enabled — log at WARN and continue
            log.warn("Could not enable TTL on table={} (may already be set): {}", tableName, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SQS — create DLQs first, then main queues with redrive policies
    //
    // Uses a get-or-create pattern to avoid depending on SDK-version-specific
    // QueueAlreadyExistsException class availability.
    // ══════════════════════════════════════════════════════════════════════════

    private void bootstrapSqs() {
        String walletTradesToProcessDlqArn = getOrCreateQueue("wallet-trades-to-process-dlq", null);
        String alertsToNotifyDlqArn        = getOrCreateQueue("alerts-to-notify-dlq",         null);

        getOrCreateQueue("wallet-trades-to-process", walletTradesToProcessDlqArn);
        getOrCreateQueue("alerts-to-notify",         alertsToNotifyDlqArn);
    }

    /**
     * Creates a standard SQS queue if it does not already exist, then returns its ARN.
     *
     * @param queueName the queue name
     * @param dlqArn    if non-null, a redrive policy pointing to this DLQ ARN is attached
     */
    private String getOrCreateQueue(String queueName, String dlqArn) {
        // 1. Try to get an existing queue first (idempotent path)
        try {
            String url = sqsClient.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
            String arn = fetchQueueArn(url);
            log.debug("SQS queue already exists (idempotent): {} -> {}", queueName, arn);
            return arn;
        } catch (QueueDoesNotExistException ignored) {
            // Queue doesn't exist yet — fall through to create it
        }

        // 2. Create the queue
        try {
            var requestBuilder = CreateQueueRequest.builder().queueName(queueName);
            if (dlqArn != null) {
                String redrivePolicy = String.format(
                        "{\"deadLetterTargetArn\":\"%s\",\"maxReceiveCount\":\"%d\"}",
                        dlqArn, DLQ_MAX_RECEIVE_COUNT);
                requestBuilder.attributes(
                        Map.of(QueueAttributeName.REDRIVE_POLICY, redrivePolicy));
            }
            String url = sqsClient.createQueue(requestBuilder.build()).queueUrl();
            String arn = fetchQueueArn(url);
            log.info("Created SQS queue: {} (DLQ redrive={}) -> {}", queueName, dlqArn != null, arn);
            return arn;
        } catch (Exception e) {
            log.error("Failed to create SQS queue={}: {}", queueName, e.getMessage(), e);
            throw e;
        }
    }

    private String fetchQueueArn(String queueUrl) {
        return sqsClient.getQueueAttributes(r -> r
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)
        ).attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // S3
    // ══════════════════════════════════════════════════════════════════════════

    private void bootstrapS3() {
        try {
            var requestBuilder = CreateBucketRequest.builder().bucket(archivesBucket);
            // us-east-1 must NOT include a LocationConstraint; all other regions must.
            if (!"us-east-1".equals(region)) {
                requestBuilder.createBucketConfiguration(c ->
                        c.locationConstraint(region));
            }
            s3Client.createBucket(requestBuilder.build());
            log.info("Created S3 bucket: {}", archivesBucket);
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            log.debug("S3 bucket already exists (idempotent): {}", archivesBucket);
        } catch (Exception e) {
            log.error("Failed to create S3 bucket={}: {}", archivesBucket, e.getMessage(), e);
            throw e;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Builder helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static AttributeDefinition attr(String name, ScalarAttributeType type) {
        return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    private static GlobalSecondaryIndex gsi(String name, KeySchemaElement... keys) {
        return GlobalSecondaryIndex.builder()
                .indexName(name)
                .keySchema(keys)
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build();
    }
}
