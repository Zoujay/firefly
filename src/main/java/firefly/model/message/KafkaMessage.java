package firefly.model.message;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.MappedSuperclass;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class KafkaMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_uuid", nullable = false, updatable = false, length = 36)
    private String messageUUID;

    @Column(name = "topic", nullable = false, updatable = false, length = 249)
    private String topic;

    @Column(name = "kafka_partition", nullable = false, updatable = false)
    private Integer kafkaPartition;

    @Column(name = "kafka_offset", nullable = false, updatable = false)
    private Long kafkaOffset;

    @Column(name = "message_key", updatable = false, length = 512)
    private String messageKey;

    @Lob
    @Column(name = "payload", updatable = false)
    private String payload;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    protected KafkaMessage(
            String messageUUID,
            String topic,
            Integer kafkaPartition,
            Long kafkaOffset,
            String messageKey,
            String payload
    ) {
        this.messageUUID = messageUUID;
        this.topic = topic;
        this.kafkaPartition = kafkaPartition;
        this.kafkaOffset = kafkaOffset;
        this.messageKey = messageKey;
        this.payload = payload;
    }
}
