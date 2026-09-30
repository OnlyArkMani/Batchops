package com.batchops.events;

import com.batchops.domain.Stage;
import com.batchops.domain.Topics;
import com.batchops.pipeline.PipelineService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * One consumer group per stage, all keyed by upload_id: a single upload is processed in order on one
 * partition, while different uploads fan out across partitions and instances.
 */
@Component
@ConditionalOnProperty(name = "batchops.roles.worker", havingValue = "true", matchIfMissing = true)
public class StageListeners {

    private final PipelineService pipeline;

    public StageListeners(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @KafkaListener(id = "stage-standardizer", topics = Topics.RECEIVED, groupId = "batchops-standardizer")
    public void standardize(ConsumerRecord<String, String> rec) {
        pipeline.handle(Stage.STANDARDIZER, rec);
    }

    @KafkaListener(id = "stage-validator", topics = Topics.STANDARDIZED, groupId = "batchops-validator")
    public void validate(ConsumerRecord<String, String> rec) {
        pipeline.handle(Stage.VALIDATOR, rec);
    }

    @KafkaListener(id = "stage-transformer", topics = Topics.VALIDATED, groupId = "batchops-transformer")
    public void transform(ConsumerRecord<String, String> rec) {
        pipeline.handle(Stage.TRANSFORMER, rec);
    }

    @KafkaListener(id = "stage-publisher", topics = Topics.TRANSFORMED, groupId = "batchops-publisher")
    public void publish(ConsumerRecord<String, String> rec) {
        pipeline.handle(Stage.PUBLISHER, rec);
    }
}
