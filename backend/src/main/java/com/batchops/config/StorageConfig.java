package com.batchops.config;

import com.batchops.storage.LocalObjectStore;
import com.batchops.storage.ObjectStore;
import com.batchops.storage.S3ObjectStore;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

@Configuration
public class StorageConfig {

    @Bean
    public ObjectStore objectStore(AppProperties props) {
        AppProperties.Storage s = props.storage();
        if (!"s3".equalsIgnoreCase(s.type())) {
            return new LocalObjectStore(Path.of(s.localDir()));
        }
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(s.endpoint()))
                .region(Region.of(s.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s.accessKey(), s.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(s.pathStyle()).build())
                .build();
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(s.bucket()).build());
        } catch (NoSuchBucketException e) {
            client.createBucket(CreateBucketRequest.builder().bucket(s.bucket()).build());
        } catch (RuntimeException e) {
            // Storage may still be starting; the first real call will surface a retryable error.
        }
        return new S3ObjectStore(client, s.bucket());
    }
}
