package com.batchops.events;

import com.batchops.config.AppProperties;
import com.batchops.domain.Topics;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableKafka
public class KafkaConfig {

    private NewTopic topic(String name, AppProperties p) {
        return TopicBuilder.name(name).partitions(p.kafka().partitions()).replicas(p.kafka().replicationFactor())
                .build();
    }

    @Bean
    public NewTopic received(AppProperties p) { return topic(Topics.RECEIVED, p); }
    @Bean
    public NewTopic standardized(AppProperties p) { return topic(Topics.STANDARDIZED, p); }
    @Bean
    public NewTopic validated(AppProperties p) { return topic(Topics.VALIDATED, p); }
    @Bean
    public NewTopic transformed(AppProperties p) { return topic(Topics.TRANSFORMED, p); }
    @Bean
    public NewTopic published(AppProperties p) { return topic(Topics.PUBLISHED, p); }
    @Bean
    public NewTopic failed(AppProperties p) { return topic(Topics.FAILED, p); }
    @Bean
    public NewTopic status(AppProperties p) { return topic(Topics.STATUS, p); }
    @Bean
    public NewTopic dlq(AppProperties p) { return topic(Topics.DLQ, p); }
    @Bean
    public NewTopic retry5s(AppProperties p) { return topic(Topics.RETRY_5S, p); }
    @Bean
    public NewTopic retry1m(AppProperties p) { return topic(Topics.RETRY_1M, p); }
    @Bean
    public NewTopic retry10m(AppProperties p) { return topic(Topics.RETRY_10M, p); }

    /**
     * Default factory for the stage consumers. Stage handlers catch their own failures and route them
     * to retry topics / incidents, so this error handler only covers infrastructure errors (for
     * example the database being down): retry in place, then park the record on the DLQ topic.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> cf, KafkaTemplate<String, String> template, AppProperties p) {
        ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(cf);
        f.setConcurrency(p.kafka().concurrency());
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (rec, ex) -> new TopicPartition(Topics.DLQ, -1));
        f.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(2_000L, 20L)));
        return f;
    }

    /** Retry-tier consumers hold a record for up to ten minutes, so they need a long poll interval. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> retryListenerContainerFactory(
            KafkaProperties kafkaProperties) {
        Map<String, Object> cfg = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 20 * 60 * 1000);
        ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(new DefaultKafkaConsumerFactory<>(cfg));
        f.setConcurrency(1);
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return f;
    }
}
