package com.notification.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;

@Configuration
public class AwsSesConfig {

    @Value("${aws.region}") private String region;

    // Optional explicit keys — present in local dev / CI, absent in production
    @Value("${aws.accessKeyId:}") private String accessKeyId;
    @Value("${aws.secretAccessKey:}") private String secretAccessKey;

    @Bean
    public SesClient sesClient() {
        var builder = SesClient.builder().region(Region.of(region));

        // Use static credentials only when both values are explicitly provided and non-empty.
        // In production (ECS/EC2/Lambda) leave them blank so DefaultCredentialsProvider
        // picks up the IAM task/instance role automatically — supports credential rotation.
        if (!accessKeyId.isBlank() && !secretAccessKey.isBlank()
                && !"dummy".equals(accessKeyId)) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}
