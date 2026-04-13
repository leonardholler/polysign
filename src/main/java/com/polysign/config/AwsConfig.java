package com.polysign.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.time.Duration;

/**
 * Creates the core AWS SDK v2 client beans.
 *
 * <p>For LocalStack (profile "local"), set {@code aws.endpoint-override} in
 * {@code application-local.yml} and ensure the following env vars are present:
 * <pre>
 *   AWS_ACCESS_KEY_ID=test
 *   AWS_SECRET_ACCESS_KEY=test
 * </pre>
 *
 * <p>For real AWS (profile "aws"), leave {@code aws.endpoint-override} unset.
 * The {@link DefaultCredentialsProvider} will resolve credentials from the
 * ECS task role / EC2 instance profile / environment in the standard order.
 *
 * <p>S3 uses path-style access ({@code forcePathStyle=true}) which is required
 * by LocalStack and harmless on real AWS when an endpoint override is set.
 *
 * <p>DynamoDB and SQS clients use an Apache HTTP client pool tuned to evict
 * idle/stale connections via {@code useIdleConnectionReaper}. This prevents
 * "Connection pool shut down" IllegalStateExceptions from dead connections
 * poisoning the pool on long-running deployments.
 */
@Configuration
public class AwsConfig {

    @Value("${aws.region:us-east-1}")
    private String region;

    /** Set in application-local.yml for LocalStack; absent in application-aws.yml. */
    @Value("${aws.endpoint-override:}")
    private String endpointOverride;

    /**
     * Provides a {@link StaticCredentialsProvider} when {@code aws.access-key-id} and
     * {@code aws.secret-access-key} are set in the active Spring profile (e.g. the
     * "local" profile for LocalStack). Falls back to {@link DefaultCredentialsProvider}
     * for real AWS deployments where credentials come from IAM roles or environment.
     */
    @Bean
    public AwsCredentialsProvider awsCredentialsProvider(
            @Value("${aws.access-key-id:}") String accessKey,
            @Value("${aws.secret-access-key:}") String secretKey) {
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));
        }
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public DynamoDbClient dynamoDbClient(AwsCredentialsProvider awsCredentialsProvider) {
        var builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(awsCredentialsProvider)
                .httpClientBuilder(pooledHttpClientBuilder());
        applyEndpointOverride(builder);
        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

    @Bean
    public SqsClient sqsClient(AwsCredentialsProvider awsCredentialsProvider) {
        var builder = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(awsCredentialsProvider)
                .httpClientBuilder(pooledHttpClientBuilder());
        applyEndpointOverride(builder);
        return builder.build();
    }

    @Bean
    public S3Client s3Client(AwsCredentialsProvider awsCredentialsProvider) {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(awsCredentialsProvider)
                .forcePathStyle(true);   // required for LocalStack path-style URLs
        applyEndpointOverride(builder);
        return builder.build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Apache HTTP client pool shared by DynamoDB and SQS clients.
     *
     * Key settings:
     * - {@code useIdleConnectionReaper}: evicts idle/stale connections so they
     *   cannot re-enter the pool in a broken state (root cause of
     *   "Connection pool shut down" crashes).
     * - {@code connectionTimeToLive}: hard cap on connection age, preventing
     *   connections that outlive NAT/firewall idle timeouts from being reused.
     */
    private ApacheHttpClient.Builder pooledHttpClientBuilder() {
        return ApacheHttpClient.builder()
                .maxConnections(50)
                .connectionMaxIdleTime(Duration.ofSeconds(30))
                .connectionTimeToLive(Duration.ofMinutes(5))
                .socketTimeout(Duration.ofSeconds(30))
                .connectionTimeout(Duration.ofSeconds(10))
                .useIdleConnectionReaper(true);
    }

    private <B extends software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, ?>>
    void applyEndpointOverride(B builder) {
        if (StringUtils.hasText(endpointOverride)) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
    }
}
