package com.polysign.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

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
 */
@Configuration
public class AwsConfig {

    @Value("${aws.region:us-east-1}")
    private String region;

    /** Set in application-local.yml for LocalStack; absent in application-aws.yml. */
    @Value("${aws.endpoint-override:}")
    private String endpointOverride;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        var builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
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
    public SqsClient sqsClient() {
        var builder = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        applyEndpointOverride(builder);
        return builder.build();
    }

    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .forcePathStyle(true);   // required for LocalStack path-style URLs
        applyEndpointOverride(builder);
        return builder.build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private <B extends software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, ?>>
    void applyEndpointOverride(B builder) {
        if (StringUtils.hasText(endpointOverride)) {
            builder.endpointOverride(URI.create(endpointOverride));
        }
    }
}
