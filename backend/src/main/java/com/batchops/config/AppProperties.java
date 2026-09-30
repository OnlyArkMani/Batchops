package com.batchops.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Typed view of the batchops.* configuration block. */
@ConfigurationProperties(prefix = "batchops")
public record AppProperties(
        @DefaultValue("local") String instanceId,
        @DefaultValue Roles roles,
        @DefaultValue Storage storage,
        @DefaultValue Security security,
        @DefaultValue Pipeline pipeline,
        @DefaultValue Kafka kafka,
        @DefaultValue Mail mail) {

    /** Which responsibilities this process takes on, so api and worker can scale separately. */
    public record Roles(
            @DefaultValue("true") boolean api,
            @DefaultValue("true") boolean worker,
            @DefaultValue("true") boolean scheduler) {
    }

    public record Storage(
            @DefaultValue("local") String type,
            @DefaultValue("./storage") String localDir,
            @DefaultValue("http://localhost:9000") String endpoint,
            @DefaultValue("batchops") String bucket,
            @DefaultValue("us-east-1") String region,
            @DefaultValue("minioadmin") String accessKey,
            @DefaultValue("minioadmin") String secretKey,
            @DefaultValue("true") boolean pathStyle) {
    }

    public record Security(
            @DefaultValue("24") int tokenTtlHours,
            @DefaultValue("http://localhost:5173,http://127.0.0.1:5173") List<String> corsOrigins,
            @DefaultValue("admin") String bootstrapAdminUsername,
            @DefaultValue("") String bootstrapAdminPassword,
            @DefaultValue("5") int codeMaxAttempts,
            @DefaultValue("10") int loginMaxFailures,
            @DefaultValue("15") int loginLockMinutes) {
    }

    public record Pipeline(
            @DefaultValue("25") int maxUploadMb,
            @DefaultValue("0") double scoreMin,
            @DefaultValue("100") double scoreMax,
            @DefaultValue("20") int stuckRunMinutes,
            @DefaultValue("false") boolean allowDestructiveAutoFix,
            @DefaultValue("2") int maxManualRetries) {
    }

    public record Kafka(
            @DefaultValue("6") int partitions,
            @DefaultValue("1") short replicationFactor,
            @DefaultValue("3") int concurrency,
            @DefaultValue("500") long outboxPollMs,
            @DefaultValue("100") int outboxBatch) {
    }

    public record Mail(
            @DefaultValue("noreply@batchops.local") String from,
            @DefaultValue("true") boolean enabled) {
    }
}
