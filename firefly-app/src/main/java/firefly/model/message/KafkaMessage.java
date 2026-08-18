package firefly.model.message;

import static firefly.constant.PersistenceDefaults.UNSET_TIME;

import firefly.constant.MessageProcessingStatus;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.MappedSuperclass;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Common Inbox record for a Kafka message received by Firefly.
 *
 * <p>The four concrete Inbox tables persist the original Kafka record before its offset is
 * acknowledged. Besides deduplicating by business UUID, the Inbox records whether business
 * processing is archived, in progress, successful, or waiting for manual recovery after a failure.
 */
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

    @Column(name = "message_key", nullable = false, updatable = false, length = 512)
    private String messageKey = StringUtils.EMPTY;

    @Lob
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload = StringUtils.EMPTY;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 32)
    private MessageProcessingStatus processingStatus = MessageProcessingStatus.ARCHIVED;

    @Column(name = "processing_attempt", nullable = false)
    private Integer processingAttempt = 0;

    @Column(name = "processor_id", nullable = false, length = 128)
    private String processorID = StringUtils.EMPTY;

    @Column(name = "processing_started_at", nullable = false)
    private LocalDateTime processingStartedAt = UNSET_TIME;

    @Column(name = "processing_finished_at", nullable = false)
    private LocalDateTime processingFinishedAt = UNSET_TIME;

    @Column(name = "last_error", nullable = false, length = 2048)
    private String lastError = StringUtils.EMPTY;

    protected KafkaMessage(
        String messageUUID,
        String topic,
        Integer kafkaPartition,
        Long kafkaOffset,
        String messageKey,
        String payload) {
        this.messageUUID = messageUUID;
        this.topic = topic;
        this.kafkaPartition = kafkaPartition;
        this.kafkaOffset = kafkaOffset;
        this.messageKey = messageKey == null ? StringUtils.EMPTY : messageKey;
        this.payload = Objects.requireNonNull(payload, "payload must not be null");
    }
}
