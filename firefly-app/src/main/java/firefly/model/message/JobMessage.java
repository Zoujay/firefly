package firefly.model.message;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "job_message",
    uniqueConstraints = {
      @UniqueConstraint(name = "uidx_job_message_uuid", columnNames = "message_uuid"),
      @UniqueConstraint(
          name = "uidx_job_message_position",
          columnNames = {"topic", "kafka_partition", "kafka_offset"})
    },
    indexes = {
      @Index(name = "idx_job_message_received_at", columnList = "received_at"),
      @Index(name = "idx_job_message_processing", columnList = "processing_status,received_at,id")
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class JobMessage extends KafkaMessage {

  public JobMessage(
      String messageUUID,
      String topic,
      Integer kafkaPartition,
      Long kafkaOffset,
      String messageKey,
      String payload) {
    super(messageUUID, topic, kafkaPartition, kafkaOffset, messageKey, payload);
  }
}
