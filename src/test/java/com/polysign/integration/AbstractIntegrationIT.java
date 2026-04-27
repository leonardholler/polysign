package com.polysign.integration;

import com.polysign.backtest.ResolutionSweeper;
import com.polysign.backtest.SnapshotArchiver;
import com.polysign.metrics.SignalQualityMetrics;
import com.polysign.metrics.SqsQueueMetrics;
import com.polysign.notification.NotificationConsumer;
import com.polysign.poller.MarketPoller;
import com.polysign.poller.PricePoller;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base class for all integration tests.
 *
 * <h3>Container lifecycle</h3>
 * Uses the Testcontainers Singleton Container Pattern: the LocalStack container
 * is started once in the static initialiser and reused across all subclasses.
 * Testcontainers registers a JVM shutdown hook (via Ryuk) to stop the container
 * automatically when the test run ends.
 *
 * <h3>Spring context setup</h3>
 * {@code @ActiveProfiles("local")} loads the LocalStack credentials
 * ({@code aws.access-key-id=test}, {@code aws.secret-access-key=test}) from
 * {@code application-local.yml}. {@link #overrideAwsEndpoint} then overrides
 * {@code aws.endpoint-override} with the dynamic Testcontainers port so the
 * AWS SDK v2 clients route to the container, not to {@code localstack:4566}.
 *
 * <p>BootstrapRunner ({@code @Order(1) ApplicationRunner}) creates all DynamoDB
 * tables, SQS queues, and the S3 bucket on application startup — no manual
 * table creation is needed here.
 *
 * <h3>Mocked @Scheduled beans (complete grep-verified list)</h3>
 * All beans that carry {@code @Scheduled} and that reach external services
 * (Polymarket APIs, ntfy.sh, S3, SQS long-poll) are mocked here to prevent
 * background polling from interfering with test assertions.
 *
 * <p>Beans NOT mocked here so that specific subclasses can inject and invoke them:
 * {@code PriceMovementDetector}, {@code StatisticalAnomalyDetector},
 * {@code AlertOutcomeEvaluator}.
 */
@SpringBootTest
@ActiveProfiles("local")
@MockBean({
        // ── External-service pollers ──────────────────────────────────────────
        MarketPoller.class,
        PricePoller.class,
        // ── SQS consumers ────────────────────────────────────────────────────
        NotificationConsumer.class,
        // ── Phase 9 metrics schedulers ───────────────────────────────────────
        SqsQueueMetrics.class,
        SignalQualityMetrics.class,
        // ── Backtest schedulers (not under test in any IT class) ─────────────
        SnapshotArchiver.class,
        ResolutionSweeper.class
})
public abstract class AbstractIntegrationIT {

    /** Pinned to 3.8 per spec.md. Started once; shared across all subclasses. */
    static final LocalStackContainer LOCAL_STACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
                    .withServices(
                            LocalStackContainer.Service.DYNAMODB,
                            LocalStackContainer.Service.SQS,
                            LocalStackContainer.Service.S3);

    static {
        LOCAL_STACK.start();
    }

    /**
     * Injects the dynamic LocalStack endpoint URL into the Spring context,
     * overriding the {@code http://localstack:4566} default from
     * {@code application-local.yml}.
     */
    @DynamicPropertySource
    static void overrideAwsEndpoint(DynamicPropertyRegistry registry) {
        registry.add("aws.endpoint-override",
                () -> LOCAL_STACK.getEndpointOverride(
                        LocalStackContainer.Service.DYNAMODB).toString());
    }
}
