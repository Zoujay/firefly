package firefly.model.message;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "pipeline_message",
        uniqueConstraints = {
                @UniqueConstraint(name = "uidx_pipeline_message_uuid", columnNames = "message_uuid"),
                @UniqueConstraint(
                        name = "uidx_pipeline_message_position",
                        columnNames = {"topic", "kafka_partition", "kafka_offset"}
                )
        },
        indexes = @Index(name = "idx_pipeline_message_received_at", columnList = "received_at")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PipelineMessage extends KafkaMessage {

    public PipelineMessage(
            String messageUUID,
            String topic,
            Integer kafkaPartition,
            Long kafkaOffset,
            String messageKey,
            String payload
    ) {
        super(messageUUID, topic, kafkaPartition, kafkaOffset, messageKey, payload);
    }
}
